//! Precarga: baja de antemano (con Wi-Fi) los archivos de audio de una lista de pistas a la
//! caché de librespot, la misma que se llena al escuchar normalmente.
//!
//! Los archivos quedan **cifrados** en la caché. Para reproducirlos el motor sigue pidiendo a
//! Spotify la clave de cada pista (unos bytes), que no se guarda: no hay modo sin conexión ni se
//! evita la protección de contenido. Lo que se ahorra es el audio (~45 MB/h a 96 kbps).

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use librespot_audio::AudioFile;
use librespot_core::{session::Session, Error, SpotifyUri};
use librespot_metadata::audio::{AudioFileFormat, AudioItem};
use serde_json::json;

static CANCEL: AtomicBool = AtomicBool::new(false);
static RUNNING: AtomicBool = AtomicBool::new(false);

pub fn cancel() {
    CANCEL.store(true, Ordering::SeqCst);
}

/// Mismo orden de formatos que el Player de librespot, para que la precarga y la reproducción
/// elijan el mismo archivo (si no, la caché no sirve).
fn formats(bitrate: i32) -> [AudioFileFormat; 7] {
    use AudioFileFormat::*;
    match bitrate {
        320 => [OGG_VORBIS_320, MP3_320, MP3_256, OGG_VORBIS_160, MP3_160, OGG_VORBIS_96, MP3_96],
        160 => [OGG_VORBIS_160, MP3_160, OGG_VORBIS_96, MP3_96, MP3_256, OGG_VORBIS_320, MP3_320],
        _ => [OGG_VORBIS_96, MP3_96, OGG_VORBIS_160, MP3_160, MP3_256, OGG_VORBIS_320, MP3_320],
    }
}

fn bytes_per_second(f: AudioFileFormat) -> usize {
    use AudioFileFormat::*;
    let kbytes = match f {
        OGG_VORBIS_96 | MP3_96 => 12,
        OGG_VORBIS_160 | MP3_160 => 20,
        MP3_256 => 32,
        _ => 40,
    };
    kbytes * 1024
}

/// @return Some(bytes bajados) o None si ya estaba en caché.
async fn one(session: &Session, uri: &str, bitrate: i32) -> Result<Option<u64>, Error> {
    let id = SpotifyUri::from_uri(uri)?;
    let item = AudioItem::get_file(session, id).await?;
    let (format, file_id) = formats(bitrate)
        .iter()
        .find_map(|f| item.files.get(f).map(|id| (*f, *id)))
        .ok_or_else(|| Error::unavailable("sin formato compatible"))?;

    let cache = session.cache().ok_or_else(|| Error::unavailable("caché desactivada"))?;
    if cache.file(file_id).is_some() {
        return Ok(None);
    }

    let file = AudioFile::open(session, file_id, bytes_per_second(format)).await?;
    if file.is_cached() {
        return Ok(None);
    }
    let ctrl = file.get_stream_loader_controller()?;
    let len = ctrl.len();
    ctrl.set_stream_mode();
    // Pedir el archivo completo y esperar (bloqueante: fuera del runtime async). Si una espera
    // vence por red lenta, se reintenta mientras falten bytes.
    let done = tokio::task::spawn_blocking(move || {
        for _ in 0..20 {
            if CANCEL.load(Ordering::SeqCst) {
                return false;
            }
            let _ = ctrl.fetch_next_and_wait(len, len);
            if ctrl.range_to_end_available() {
                return true;
            }
        }
        false
    })
    .await
    .unwrap_or(false);
    if !done {
        return Err(Error::deadline_exceeded("descarga incompleta"));
    }
    // librespot guarda el archivo en la caché apenas termina la descarga (tarea aparte):
    // esperar a que aparezca antes de soltar el AudioFile.
    for _ in 0..50 {
        if cache.file(file_id).is_some() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    drop(file);
    Ok(Some(len as u64))
}

pub async fn run(session: Session, uris: Vec<String>, bitrate: i32, req_id: i32) {
    if RUNNING.swap(true, Ordering::SeqCst) {
        crate::emit(json!({"type": "prefetch", "req_id": req_id, "state": "busy"}));
        return;
    }
    CANCEL.store(false, Ordering::SeqCst);
    let total = uris.len();
    let (mut done, mut cached, mut failed, mut bytes) = (0usize, 0usize, 0usize, 0u64);
    for uri in uris {
        if CANCEL.load(Ordering::SeqCst) {
            break;
        }
        match one(&session, &uri, bitrate).await {
            Ok(Some(n)) => bytes += n,
            Ok(None) => cached += 1,
            Err(e) => {
                log::warn!("precarga {uri}: {e}");
                failed += 1;
            }
        }
        done += 1;
        crate::emit(json!({
            "type": "prefetch", "req_id": req_id, "state": "progress",
            "done": done, "total": total, "cached": cached, "failed": failed, "bytes": bytes
        }));
    }
    let state = if CANCEL.load(Ordering::SeqCst) { "cancelled" } else { "finished" };
    crate::emit(json!({
        "type": "prefetch", "req_id": req_id, "state": state,
        "done": done, "total": total, "cached": cached, "failed": failed, "bytes": bytes
    }));
    RUNNING.store(false, Ordering::SeqCst);
}
