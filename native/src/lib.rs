//! Motor de reproducción de Tinyl (cliente liviano para Spotify).
//!
//! Corre librespot dentro del proceso de la app (sin PC y sin otras apps) y expone a Java
//! el mismo protocolo JSON que la API WebSocket de Spotify Soloist:
//!   Java -> Rust: `{"type":"command","command":"pause"}` (ver `handle_command`)
//!   Rust -> Java: eventos `auth_state`, `playback_state`, `track_changed`, `playback_changed`,
//!                 `position_sync`, `volume_changed`, `options_changed`, `device_changed`,
//!                 `command_result`, `error` (+ extensiones `engine_error` y `token`).
//! Así la UI no depende del motor: si algún día existe Soloist para Android, se cambia el motor.
//!
//! El audio PCM (s16, 44.1 kHz, estéreo) se entrega a Java (`AudioTrack`), que funciona en
//! cualquier Android >= 5.0 sin depender de AAudio/OpenSL.

mod compat;
mod net;
mod prefetch;
mod sink;

use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use jni::objects::{GlobalRef, JClass, JString, JValue};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use log::{error, info, warn};
use serde_json::{json, Value};

use librespot_connect::{ConnectConfig, LoadRequest, LoadRequestOptions, PlayingTrack, Spirc};
use librespot_core::{
    authentication::Credentials,
    cache::Cache,
    config::{DeviceType, SessionConfig},
    session::Session,
    Error,
};
use librespot_metadata::audio::{AudioItem, UniqueFields};
use librespot_playback::{
    audio_backend::Sink,
    config::{Bitrate, PlayerConfig},
    mixer::{self, MixerConfig},
    player::{Player, PlayerEvent},
};

/// Client ID "keymaster" que usa librespot en escritorio. Su redirect registrado es
/// `http://127.0.0.1:8898/login`, que la app atiende con un mini servidor local.
pub const CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

static VM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGE: OnceLock<GlobalRef> = OnceLock::new();
static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static ENGINE: Mutex<Engine> = Mutex::new(Engine::new());

struct Engine {
    spirc: Option<Arc<Spirc>>,
    session: Option<Session>,
    running: bool,
    device_name: String,
    snap: Snapshot,
}

struct Snapshot {
    logged_in: bool,
    active: bool,
    status: &'static str,
    item: Value,
    position_ms: u32,
    timestamp_ms: u64,
    volume: u32,
    shuffle: bool,
    repeat_context: bool,
    repeat_track: bool,
}

impl Engine {
    const fn new() -> Self {
        Engine {
            spirc: None,
            session: None,
            running: false,
            device_name: String::new(),
            snap: Snapshot {
                logged_in: false,
                active: false,
                status: "idle",
                item: Value::Null,
                position_ms: 0,
                timestamp_ms: 0,
                volume: 50,
                shuffle: false,
                repeat_context: false,
                repeat_track: false,
            },
        }
    }
}

fn engine() -> std::sync::MutexGuard<'static, Engine> {
    ENGINE.lock().unwrap_or_else(|p| p.into_inner())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub(crate) fn vm() -> &'static JavaVM {
    VM.get().expect("nativeInit no fue llamado")
}

pub(crate) fn bridge_class() -> &'static GlobalRef {
    BRIDGE.get().expect("nativeInit no fue llamado")
}

fn runtime() -> &'static tokio::runtime::Runtime {
    RT.get_or_init(|| {
        // Dos hilos alcanzan: el trabajo pesado (decodificar) ocurre en el hilo propio del Player.
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("tinyl-rt")
            .enable_all()
            .build()
            .expect("no se pudo crear el runtime")
    })
}

/// Envía un evento JSON a `NativeEngine.onEvent(String)`.
pub(crate) fn emit(v: Value) {
    let Some(vm) = VM.get() else { return };
    let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
    let text = v.to_string();
    let Ok(jtext) = env.new_string(text) else { return };
    let class: &JClass = bridge_class().as_obj().into();
    if env
        .call_static_method(class, "onEvent", "(Ljava/lang/String;)V", &[JValue::Object(&jtext)])
        .is_err()
    {
        let _ = env.exception_clear();
    }
    let _ = env.delete_local_ref(jtext);
}

fn emit_error(msg: impl Into<String>) {
    emit(json!({"type": "error", "message": msg.into()}));
}

fn auth_state_json(e: &Engine) -> Value {
    json!({
        "type": "auth_state",
        "logged_in": e.snap.logged_in,
        "is_active": e.snap.active,
        "device_name": e.device_name,
    })
}

fn repeat_str(s: &Snapshot) -> &'static str {
    if s.repeat_track {
        "track"
    } else if s.repeat_context {
        "context"
    } else {
        "off"
    }
}

fn options_json(s: &Snapshot) -> Value {
    json!({"shuffle": s.shuffle, "repeat": repeat_str(s), "playback_speed": 1.0, "modes": {}})
}

fn position_json(s: &Snapshot) -> Value {
    let speed = if s.status == "playing" { 1.0 } else { 0.0 };
    json!({"position_ms": s.position_ms, "timestamp_ms": s.timestamp_ms, "speed": speed})
}

fn playback_state_json(e: &Engine) -> Value {
    let s = &e.snap;
    json!({
        "type": "playback_state",
        "status": s.status,
        "item": s.item,
        "context": Value::Null,
        "position": position_json(s),
        "volume": s.volume,
        "is_active": s.active,
        "options": options_json(s),
        "available_actions": {
            "pause": {}, "play": {}, "seek": {}, "skip_next": {}, "skip_prev": {},
            "shuffle": {}, "set_repeat": {}
        }
    })
}

/// Convierte un `AudioItem` de librespot en la "Entity" del protocolo Soloist.
fn entity_json(item: &AudioItem) -> Value {
    let covers: Vec<Value> = item
        .covers
        .iter()
        .map(|c| {
            let size = match c.width {
                w if w > 0 && w <= 64 => "small",
                w if w > 0 && w <= 300 => "default",
                w if w > 0 && w <= 640 => "large",
                _ => "xlarge",
            };
            json!({"url": c.url, "size": size, "width": c.width})
        })
        .collect();
    let named = |name: &str| json!({"entity": {"decorations": {"identity": {"name": name}}}});
    let (entity_type, parent, creators) = match &item.unique_fields {
        UniqueFields::Track { artists, album, .. } => (
            "track",
            named(album),
            artists.iter().map(|a| named(&a.name)).collect::<Vec<_>>(),
        ),
        UniqueFields::Episode { show_name, .. } => ("episode", named(show_name), vec![named(show_name)]),
        UniqueFields::Local { artists, album, .. } => (
            "track",
            named(album.as_deref().unwrap_or("")),
            artists.iter().map(|a| named(a)).collect(),
        ),
    };
    let ratings: Vec<&str> = if item.is_explicit { vec!["explicit"] } else { vec![] };
    json!({
        "uri": item.uri,
        "entity_type": entity_type,
        "decorations": {
            "identity": {"name": item.name},
            "visual_identity": {"cover": covers},
            "parent": parent,
            "creators": creators,
            "playback": {"duration_ms": item.duration_ms, "content_ratings": ratings}
        }
    })
}

fn handle_player_event(ev: PlayerEvent) {
    let mut e = engine();
    let mut out: Vec<Value> = Vec::new();
    let was_active = e.snap.active;
    let set_status = |e: &mut Engine, st: &'static str, pos: Option<u32>, out: &mut Vec<Value>| {
        if let Some(p) = pos {
            e.snap.position_ms = p;
            e.snap.timestamp_ms = now_ms();
        }
        if e.snap.status != st {
            e.snap.status = st;
            out.push(json!({"type": "playback_changed", "status": st}));
        }
        out.push(json!({"type": "position_sync", "position": position_json(&e.snap)}));
    };
    match ev {
        PlayerEvent::Playing { position_ms, .. } => {
            e.snap.active = true;
            set_status(&mut e, "playing", Some(position_ms), &mut out);
        }
        PlayerEvent::Paused { position_ms, .. } => set_status(&mut e, "paused", Some(position_ms), &mut out),
        PlayerEvent::Loading { position_ms, .. } => set_status(&mut e, "buffering", Some(position_ms), &mut out),
        PlayerEvent::Stopped { .. } => {
            e.snap.active = false;
            set_status(&mut e, "idle", Some(0), &mut out);
        }
        PlayerEvent::Seeked { position_ms, .. }
        | PlayerEvent::PositionCorrection { position_ms, .. }
        | PlayerEvent::PositionChanged { position_ms, .. } => {
            e.snap.position_ms = position_ms;
            e.snap.timestamp_ms = now_ms();
            out.push(json!({"type": "position_sync", "position": position_json(&e.snap)}));
        }
        PlayerEvent::TrackChanged { audio_item } => {
            e.snap.item = entity_json(&audio_item);
            out.push(json!({"type": "track_changed", "item": e.snap.item}));
        }
        PlayerEvent::VolumeChanged { volume } => {
            e.snap.volume = (volume as u32 * 100 + 32767) / 65535;
            out.push(json!({"type": "volume_changed", "volume": e.snap.volume}));
        }
        PlayerEvent::ShuffleChanged { shuffle } => {
            e.snap.shuffle = shuffle;
            out.push(json!({"type": "options_changed", "options": options_json(&e.snap)}));
        }
        PlayerEvent::RepeatChanged { context, track } => {
            e.snap.repeat_context = context;
            e.snap.repeat_track = track;
            out.push(json!({"type": "options_changed", "options": options_json(&e.snap)}));
        }
        PlayerEvent::SessionConnected { .. } => {
            e.snap.logged_in = true;
            out.push(auth_state_json(&e));
        }
        PlayerEvent::SessionDisconnected { .. } => {
            e.snap.logged_in = false;
            e.snap.active = false;
            out.push(auth_state_json(&e));
        }
        PlayerEvent::Unavailable { track_id, .. } => {
            out.push(json!({"type": "error", "message": format!("pista no disponible: {track_id}")}));
        }
        _ => {}
    }
    if was_active != e.snap.active {
        out.push(json!({"type": "device_changed", "is_active": e.snap.active, "device_name": e.device_name}));
    }
    drop(e);
    for v in out {
        emit(v);
    }
}

enum Auth {
    /// Credenciales guardadas en data_dir (sesión previa).
    Cached,
    /// Token OAuth recién obtenido en el navegador.
    Token(String),
    /// Credenciales entregadas por otra app de Spotify vía Spotify Connect (zeroconf).
    Credentials(Credentials),
}

struct StartParams {
    data_dir: PathBuf,
    cache_dir: PathBuf,
    device_name: String,
    device_id: String,
    auth: Auth,
    bitrate: i32,
    volume: i32,
    cache_mb: i32,
}

async fn run_engine(p: StartParams) -> Result<(), Error> {
    let audio_dir = p.cache_dir.join("audio");
    let size_limit = if p.cache_mb > 0 { Some(p.cache_mb as u64 * 1024 * 1024) } else { None };
    let cache = Cache::new(Some(&p.data_dir), Some(&p.data_dir), Some(&audio_dir), size_limit)?;

    let credentials = match p.auth {
        Auth::Token(t) => Credentials::with_access_token(t),
        Auth::Credentials(c) => c,
        Auth::Cached => cache
            .credentials()
            .ok_or_else(|| Error::unauthenticated("no hay sesión guardada: iniciá sesión"))?,
    };

    let mut session_config = SessionConfig::default();
    session_config.client_id = CLIENT_ID.to_string();
    if !p.device_id.is_empty() {
        session_config.device_id = p.device_id;
    }
    session_config.tmp_dir = p.cache_dir.join("tmp");
    let _ = std::fs::create_dir_all(&session_config.tmp_dir);

    let session = Session::new(session_config, Some(cache));

    let player_config = PlayerConfig {
        bitrate: match p.bitrate {
            320 => Bitrate::Bitrate320,
            160 => Bitrate::Bitrate160,
            _ => Bitrate::Bitrate96, // por defecto: menos datos y menos CPU
        },
        // Sin dither: ahorra CPU; inaudible en parlantes/auriculares de teléfono.
        ditherer: None,
        ..PlayerConfig::default()
    };

    let mixer_fn = mixer::find(None).ok_or_else(|| Error::internal("sin mixer"))?;
    let mixer = mixer_fn(MixerConfig::default())?;
    let player = Player::new(
        player_config,
        session.clone(),
        mixer.get_soft_volume(),
        || Box::new(sink::JniSink::new()) as Box<dyn Sink>,
    );
    let mut events = player.get_player_event_channel();

    let volume = p.volume.clamp(0, 100) as u32;
    let connect_config = ConnectConfig {
        name: p.device_name.clone(),
        device_type: DeviceType::Smartphone,
        initial_volume: (volume * 65535 / 100) as u16,
        ..ConnectConfig::default()
    };

    let (spirc, spirc_task) =
        Spirc::new(connect_config, session.clone(), credentials, player, mixer).await?;
    let spirc = Arc::new(spirc);

    {
        let mut e = engine();
        e.spirc = Some(spirc.clone());
        e.session = Some(session.clone());
        e.snap.logged_in = true;
        e.snap.volume = volume;
        let auth = auth_state_json(&e);
        drop(e);
        emit(auth);
        emit(json!({"type": "session_info", "username": session.username()}));
    }

    let event_loop = tokio::spawn(async move {
        while let Some(ev) = events.recv().await {
            handle_player_event(ev);
        }
    });

    spirc_task.await;
    event_loop.abort();
    Ok(())
}

fn start_engine(p: StartParams) {
    {
        let mut e = engine();
        if e.running {
            drop(e);
            emit_error("el motor ya está en marcha");
            return;
        }
        e.running = true;
        e.device_name = p.device_name.clone();
    }
    runtime().spawn(async move {
        let result = run_engine(p).await;
        let mut e = engine();
        e.running = false;
        e.spirc = None;
        e.session = None;
        e.snap.logged_in = false;
        e.snap.active = false;
        e.snap.status = "idle";
        let auth = auth_state_json(&e);
        drop(e);
        if let Err(err) = result {
            error!("motor detenido con error: {err}");
            emit(json!({"type": "engine_error", "message": err.to_string()}));
        } else {
            info!("motor detenido");
        }
        emit(json!({"type": "playback_changed", "status": "idle"}));
        emit(auth);
    });
}

fn field_bool(cmd: &Value, name: &str) -> Result<bool, String> {
    cmd.get(name).and_then(Value::as_bool).ok_or_else(|| format!("falta el campo booleano '{name}'"))
}

fn field_u64(cmd: &Value, name: &str) -> Result<u64, String> {
    cmd.get(name).and_then(Value::as_u64).ok_or_else(|| format!("falta el campo numérico '{name}'"))
}

/// Ejecuta un comando con el formato de la API WebSocket de Soloist.
fn handle_command(text: &str) {
    let cmd: Value = match serde_json::from_str(text) {
        Ok(v) => v,
        Err(_) => return emit_error("JSON inválido"),
    };
    if cmd.get("type").and_then(Value::as_str) != Some("command") {
        return emit_error("tipo de mensaje desconocido");
    }
    let name = cmd.get("command").and_then(Value::as_str).unwrap_or("").to_string();

    // Consultas: responden con un evento, sin command_result.
    match name.as_str() {
        "get_auth_state" => return emit(auth_state_json(&engine())),
        "get_state" => {
            let e = engine();
            if !e.snap.logged_in {
                drop(e);
                return emit_error("command requires authentication");
            }
            return emit(playback_state_json(&e));
        }
        _ => {}
    }

    let (spirc, active) = {
        let e = engine();
        (e.spirc.clone(), e.snap.active)
    };
    let Some(spirc) = spirc else {
        return emit_error("command requires authentication");
    };

    let result: Result<(), String> = (|| {
        let map = |r: Result<(), Error>| r.map_err(|e| e.to_string());
        match name.as_str() {
            "play" => match cmd.get("uri").and_then(Value::as_str) {
                Some(uri) if !uri.is_empty() => {
                    if !active {
                        map(spirc.activate())?;
                    }
                    // Extensión al protocolo Soloist: "offset_uri" (o "offset_index") arranca el
                    // contexto en esa pista y sigue con el resto (tocar una canción de un álbum).
                    let playing_track = cmd
                        .get("offset_uri")
                        .and_then(Value::as_str)
                        .filter(|u| !u.is_empty())
                        .map(|u| PlayingTrack::Uri(u.to_string()))
                        .or_else(|| {
                            cmd.get("offset_index")
                                .and_then(Value::as_u64)
                                .map(|i| PlayingTrack::Index(i as u32))
                        });
                    let opts = LoadRequestOptions {
                        start_playing: true,
                        playing_track,
                        ..Default::default()
                    };
                    let req = if uri.starts_with("spotify:track:") || uri.starts_with("spotify:episode:") {
                        LoadRequest::from_tracks(vec![uri.to_string()], opts)
                    } else {
                        LoadRequest::from_context_uri(uri.to_string(), opts)
                    };
                    map(spirc.load(req))
                }
                _ => {
                    if !active {
                        map(spirc.activate())?;
                    }
                    map(spirc.play())
                }
            },
            "pause" => map(spirc.pause()),
            "skip_next" => map(spirc.next()),
            "skip_prev" => map(spirc.prev()),
            "seek" => map(spirc.set_position_ms(field_u64(&cmd, "position_ms")? as u32)),
            "set_volume" => {
                let v = field_u64(&cmd, "volume")?.min(100) as u32;
                map(spirc.set_volume((v * 65535 / 100) as u16))
            }
            "set_shuffle" => map(spirc.shuffle(field_bool(&cmd, "enabled")?)),
            "set_repeat_context" => map(spirc.repeat(field_bool(&cmd, "enabled")?)),
            "set_repeat_track" => map(spirc.repeat_track(field_bool(&cmd, "enabled")?)),
            "activate" => map(spirc.activate()),
            "deactivate" => map(spirc.disconnect(true)),
            "add_to_queue" => Err("add_to_queue: usar la Web API (no soportado por el motor)".into()),
            "" => Err("falta 'command'".into()),
            other => Err(format!("comando desconocido: {other}")),
        }
    })();

    match result {
        Ok(()) => emit(json!({"type": "command_result", "command": name})),
        Err(msg) => emit_error(msg),
    }
}

fn request_token(scopes: String, req_id: i32) {
    let session = engine().session.clone();
    let Some(session) = session else {
        return emit(json!({"type": "token", "req_id": req_id, "error": "sin sesión"}));
    };
    runtime().spawn(async move {
        // keymaster (token_provider) fue dado de baja por Spotify (403 "Invalid request").
        // login5 es el mecanismo actual; keymaster queda como último recurso.
        let result = match session.login5().auth_token().await {
            Ok(t) => Ok(t),
            Err(e) => {
                warn!("login5: {e}; probando keymaster");
                session.token_provider().get_token(&scopes).await
            }
        };
        match result {
            Ok(t) => emit(json!({
                "type": "token",
                "req_id": req_id,
                "access_token": t.access_token,
                "expires_in": t.expires_in.as_secs(),
            })),
            Err(err) => {
                warn!("token: {err}");
                emit(json!({"type": "token", "req_id": req_id, "error": err.to_string()}))
            }
        }
    });
}

// ---------------------------------------------------------------- login por Spotify Connect

static PAIRING_CANCEL: Mutex<Option<tokio::sync::oneshot::Sender<()>>> = Mutex::new(None);

/// Publica el teléfono como dispositivo Spotify Connect en la Wi-Fi (mDNS) hasta que otra app
/// de Spotify lo elige; esa app le entrega credenciales y se arranca el motor con ellas.
/// Es el mismo mecanismo con el que se empareja Soloist. Pensado para Android viejos cuyo
/// navegador no puede abrir la página de login de Spotify.
fn start_pairing(p: StartParams, ip: String) {
    use futures_util::StreamExt;
    use librespot_discovery::Discovery;

    let (tx, rx) = tokio::sync::oneshot::channel::<()>();
    if let Some(old) = PAIRING_CANCEL.lock().unwrap_or_else(|e| e.into_inner()).replace(tx) {
        let _ = old.send(());
    }
    runtime().spawn(async move {
        let mut builder = Discovery::builder(p.device_id.clone(), CLIENT_ID.to_string())
            .name(p.device_name.clone())
            .device_type(DeviceType::Smartphone);
        if let Ok(addr) = ip.parse::<std::net::IpAddr>() {
            builder = builder.zeroconf_ip(vec![addr]);
        }
        let mut discovery = match builder.launch() {
            Ok(d) => d,
            Err(e) => {
                return emit(json!({"type": "pairing", "state": "error", "message": e.to_string()}));
            }
        };
        emit(json!({"type": "pairing", "state": "waiting", "device_name": p.device_name}));
        let timeout = tokio::time::sleep(std::time::Duration::from_secs(600));
        let creds = tokio::select! {
            c = discovery.next() => c,
            _ = rx => None,
            _ = timeout => None,
        };
        discovery.shutdown().await;
        match creds {
            Some(c) => {
                emit(json!({"type": "pairing", "state": "done"}));
                start_engine(StartParams { auth: Auth::Credentials(c), ..p });
            }
            None => emit(json!({"type": "pairing", "state": "stopped"})),
        }
    });
}

fn stop_pairing() {
    if let Some(tx) = PAIRING_CANCEL.lock().unwrap_or_else(|e| e.into_inner()).take() {
        let _ = tx.send(());
    }
}

// ---------------------------------------------------------------- JNI

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(Into::into).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeInit(
    env: JNIEnv,
    class: JClass,
) {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("tinyl"),
    );
    if VM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = VM.set(vm);
        }
    }
    if BRIDGE.get().is_none() {
        if let Ok(g) = env.new_global_ref(&class) {
            let _ = BRIDGE.set(g);
        }
    }
    let _ = sink::cache_method_ids(&env);
}

#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeHasCredentials(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) -> jboolean {
    let dir = jstr(&mut env, &data_dir);
    match Cache::new(Some(dir.as_str()), None, None, None) {
        Ok(c) if c.credentials().is_some() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
    cache_dir: JString,
    device_name: JString,
    device_id: JString,
    access_token: JString,
    bitrate: jint,
    volume: jint,
    cache_mb: jint,
) {
    let token = jstr(&mut env, &access_token);
    start_engine(StartParams {
        data_dir: PathBuf::from(jstr(&mut env, &data_dir)),
        cache_dir: PathBuf::from(jstr(&mut env, &cache_dir)),
        device_name: jstr(&mut env, &device_name),
        device_id: jstr(&mut env, &device_id),
        auth: if token.is_empty() { Auth::Cached } else { Auth::Token(token) },
        bitrate,
        volume,
        cache_mb,
    });
}

#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    let spirc = engine().spirc.clone();
    if let Some(s) = spirc {
        let _ = s.shutdown();
    }
}

#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeCommand(
    mut env: JNIEnv,
    _class: JClass,
    json_text: JString,
) {
    let text = jstr(&mut env, &json_text);
    handle_command(&text);
}

#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeRequestToken(
    mut env: JNIEnv,
    _class: JClass,
    scopes: JString,
    req_id: jint,
) {
    let scopes = jstr(&mut env, &scopes);
    request_token(scopes, req_id);
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeStartPairing(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
    cache_dir: JString,
    device_name: JString,
    device_id: JString,
    wifi_ip: JString,
    bitrate: jint,
    volume: jint,
    cache_mb: jint,
) {
    let ip = jstr(&mut env, &wifi_ip);
    start_pairing(
        StartParams {
            data_dir: PathBuf::from(jstr(&mut env, &data_dir)),
            cache_dir: PathBuf::from(jstr(&mut env, &cache_dir)),
            device_name: jstr(&mut env, &device_name),
            device_id: jstr(&mut env, &device_id),
            auth: Auth::Cached,
            bitrate,
            volume,
            cache_mb,
        },
        ip,
    );
}

#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeStopPairing(
    _env: JNIEnv,
    _class: JClass,
) {
    stop_pairing();
}

#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativeHttp(
    mut env: JNIEnv,
    _class: JClass,
    method: JString,
    url: JString,
    headers: JString,
    body: JString,
    req_id: jint,
) {
    let method = jstr(&mut env, &method);
    let url = jstr(&mut env, &url);
    let headers = jstr(&mut env, &headers);
    let body = jstr(&mut env, &body);
    runtime().spawn(net::request(method, url, headers, body, req_id));
}

/// Precarga de pistas a la caché (ver prefetch.rs). `uris_json`: arreglo JSON de URIs de pistas.
#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativePrefetch(
    mut env: JNIEnv,
    _class: JClass,
    uris_json: JString,
    bitrate: jint,
    req_id: jint,
) {
    let text = jstr(&mut env, &uris_json);
    let uris: Vec<String> = serde_json::from_str(&text).unwrap_or_default();
    let session = engine().session.clone();
    let Some(session) = session else {
        return emit(json!({"type": "prefetch", "req_id": req_id, "state": "error",
                           "message": "el motor no está conectado"}));
    };
    runtime().spawn(prefetch::run(session, uris, bitrate, req_id));
}

#[no_mangle]
pub extern "system" fn Java_app_tinyl_engine_NativeEngine_nativePrefetchCancel(
    _env: JNIEnv,
    _class: JClass,
) {
    prefetch::cancel();
}
