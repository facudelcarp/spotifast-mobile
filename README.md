# Tinyl

**Tinyl · cliente liviano para Spotify** en Android. No es otro reproductor: es tu Spotify (tu cuenta, tus playlists, tu biblioteca) en una app que entra en cualquier celular.
Inspirado en [Spotifast](https://github.com/crmne/spotifast), el cliente liviano de escritorio.
Corre **solo en el teléfono**: sin PC, sin la app oficial y sin apps auxiliares. APK de unos 3 MB.

- Android **4.4 KitKat (API 19) en adelante**: ARM de 32/64 bits y x86 de 32/64 bits (Intel Atom).
- Reproduce de verdad en el teléfono: el motor es [librespot](https://github.com/librespot-org/librespot) (código abierto), compilado en Rust dentro del APK.
- La UI y el motor se comunican con el **mismo protocolo JSON que la API WebSocket de Spotify Soloist**.
- Requiere **Spotify Premium**, igual que Spotifast y Soloist.

## Por qué no usa Soloist directamente

[Spotify Soloist](https://developer.spotify.com/documentation/soloist) solo existe para Linux, macOS y Windows.
No hay versión para Android, y Spotify no permite redistribuirlo dentro de otra app. Además, cada versión vence a los 90 días.
Por eso el motor es librespot, la misma alternativa que usa Spotifast como "reproductor integrado".

La UI le habla al motor con comandos Soloist (`{"type":"command","command":"pause"}`) y recibe sus eventos
(`playback_state`, `track_changed`, `position_sync`…). Si Spotify publica Soloist para Android,
alcanza con cambiar `NativeEngine` por un cliente WebSocket a `ws://127.0.0.1:PUERTO`, sin tocar la UI.

## Android 4.4

- El motor de 32 bits se compila con el NDK r25 (el último que genera binarios para API 19).
- `native/src/compat.rs` implementa las funciones de bionic que faltan en KitKat (`epoll_create1`, `fstat64`, `dl_iterate_phdr`) y `getifaddrs`, que Android recién agregó en 7.0. En versiones nuevas se usan las del sistema.
- **Todo el HTTPS pasa por el motor** (rustls con certificados propios): Android 4.4 trae TLS 1.2 apagado y certificados raíz viejos.
- Tema Holo en 4.4 y Material desde 5.0. Controles de pantalla bloqueada con `RemoteControlClient` en 4.4 y `MediaSession` desde 5.0.

## Login

1. **Navegador** (normal): OAuth PKCE con un servidor local en `127.0.0.1:8898`.
2. **Otro dispositivo (Spotify Connect)**: para teléfonos cuyo navegador no puede abrir la página de Spotify. El teléfono se anuncia en la Wi-Fi (mDNS). En cualquier otra app de Spotify de la misma red se elige «Tinyl …» una sola vez, y esa app le pasa la sesión. Es el mismo mecanismo con el que se empareja Soloist.

librespot está parcheado (`native/vendor/librespot-core`, ver "TINYL PATCH") para identificarse como escritorio Linux y para poder prender o apagar la reproducción automática sin reiniciar la sesión. En modo "android" imita a la app oficial y Spotify rechaza el login (`Login request was denied: BAD_REQUEST`).

## Funciones

Inspirada en la app oficial, pero pensada para gama baja (un solo ListView por pantalla, sin carruseles ni GridView anidados):

- **Barra inferior**: Inicio · Buscar · Tu biblioteca · Crear.
- **Inicio** con tu foto, filtros Todo / Música / Podcasts, accesos a lo último que escuchaste, tus artistas del momento y canciones recientes.
- **Buscar**: búsquedas recientes guardadas en el teléfono (cero red) y resultados por Canciones, Artistas, Álbumes y Playlists. También acepta enlaces de Spotify, pegados o compartidos.
- **Tu biblioteca** con filtros Playlists / Álbumes / Artistas y "Tus me gusta" arriba.
- **Playlist, álbum y artista** con Reproducir y Aleatorio. Al tocar una canción, el contexto arranca desde ahí.
- **Letras sincronizadas**: las de Spotify (como Spotifast) y, si no hay, las de [LRCLIB](https://lrclib.net). Vista previa en Sonando, letra completa que sigue la canción, tocar una línea salta a ese momento. Se guardan 30 días en el teléfono.
- **Spotify Connect**: elegí dónde suena (este teléfono, la PC, un parlante, la TV) y traela de vuelta con un toque.
- **Temporizador para dormir**: 15/30/45/60 minutos o al terminar la canción, con fundido de 8 s.
- **Podcasts**: tus podcasts y episodios, "Seguir escuchando" y retomar donde quedaste; también en la búsqueda.
- **Precargar** una playlist, álbum o Tus me gusta con Wi-Fi: el audio queda en la caché (cifrado, como al escucharlo) y después casi no gasta datos. Sigue necesitando conexión para la clave de cada canción: no es modo sin conexión ni evita la protección de Spotify.
- **Crear playlist** y **Agregar a una playlist** desde el menú de cualquier canción (mantener apretado).
- **Cola y reproducción automática**: al terminar lo que elegiste, sigue con canciones similares (el autoplay de Spotify, resuelto por el motor). **Iniciar radio** desde cualquier canción. En la Cola, tocar una canción salta hasta ella.
- **Mini reproductor** flotante teñido con el color de la carátula (calculado de la miniatura de 64 px), con ♥ y play.
- **Sonando**: fondo teñido con el color de la carátula, "Reproduciendo desde" la playlist o radio, deslizar la carátula para cambiar de tema, dispositivo donde suena, temporizador, compartir, menú ⋮ (radio, agregar a playlist, ir al álbum/artista) y acceso a la **Cola**.
- Listas paginadas a demanda: ningún pedido que no hayas pedido vos.

## Búsqueda y biblioteca (Web API)

Igual que Spotifast, la Web API usa **tu propia app de Spotify**. Con el client ID compartido, Spotify corta con `429 Too Many Requests`.

1. En [Spotify for Developers](https://developer.spotify.com/dashboard) tocá **Create app** (la cuenta necesita Premium).
2. Redirect URI: `http://127.0.0.1:8989/login`
3. Tildá **Web API** y guardá.
4. En Tinyl: ⋮ → Ajustes → pegá el **Client ID** → **Autorizar**.

Orden de tokens: app propia → token del login de reproducción → token `login5` del motor.
Ante un 429 se respeta `Retry-After` y no se reintenta.

## Arquitectura

```
┌──────────────────────── APK ────────────────────────┐
│ MainActivity (UI estilo LCD)                        │
│   │ comandos Soloist (JSON)        ▲ eventos Soloist│
│   ▼                                │                │
│ NativeEngine (JNI) ───────────────►┘                │
│   │                                                 │
│   ▼  libtinyl.so (Rust)                         │
│   librespot: sesión, Connect (Spirc), decodificación│
│   │ PCM s16 44.1 kHz                                │
│   ▼                                                 │
│ AudioOut (AudioTrack) ─── PlayerService (foreground,│
│                            MediaSession, notific.)  │
│ WebApi: búsqueda/biblioteca con el token del motor  │
└─────────────────────────────────────────────────────┘
```

| Carpeta | Contenido |
|---|---|
| `native/` | Motor en Rust: librespot 0.8 + puente JNI + salida de audio (`sink.rs`) |
| `app/.../engine/` | `NativeEngine` (puente), `EngineState` (estado a partir de eventos), `AudioOut` |
| `app/.../PlayerService.java` | Servicio de reproducción, notificación, controles de pantalla bloqueada, foco de audio |
| `app/.../SpotifyAuth.java` | Login OAuth PKCE con servidor local `127.0.0.1:8898` |
| `app/.../web/` | Web API (búsqueda, playlists) y conversión de enlaces `open.spotify.com` |

## Decisiones de bajo consumo

- **Sin polling.** La UI se actualiza con eventos. El reloj se calcula en el teléfono y solo avanza mientras la pantalla está visible.
- **96 kbps por defecto**: menos datos y menos CPU al decodificar. Se puede cambiar a 160 o 320.
- **Caché de audio (512 MB)**: escuchar de nuevo una canción no gasta datos.
- **Buffer de audio de ~0,5 s** y conversión sin dither: el procesador se despierta menos.
- **Locks solo mientras suena.** El wakelock y el wifilock se sueltan al pausar. Tras 10 minutos en pausa con la app cerrada, el motor se apaga.
- **Modo ahorro activado por defecto**: sin carátulas. Tema **negro puro**, que gasta menos en pantallas OLED.
- **Sin AndroidX, sin Kotlin y sin descubrimiento mDNS.** APK chico, arranque rápido y nada escuchando la red de fondo.
- **Un APK por arquitectura**: cada teléfono baja solo su motor.

## Uso

1. Instalá el APK que corresponda a tu teléfono. Si no sabés cuál, usá `app-universal-release.apk`.
2. Tocá **Iniciar sesión con Spotify**. Se abre el navegador una sola vez y la sesión queda guardada.
3. Buscá, abrí la biblioteca o **compartí cualquier enlace de Spotify** a Tinyl para reproducirlo.
4. El teléfono también aparece como dispositivo Spotify Connect en tus otras apps de Spotify.

## Compilar

Requisitos: JDK 17+, Android SDK (API 35) y NDK 25.2.9519653, Rust con `cargo-ndk`.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk
./gradlew assembleRelease          # compila también el motor Rust (tarea cargoBuild)
./gradlew assembleRelease -PskipRust   # si las .so ya están en app/src/main/jniLibs
```

Los APK quedan en `app/build/outputs/apk/release/`. El CI (`.github/workflows/android.yml`) los publica como artefacto en cada push.
El APK de release se firma con la clave de debug para poder instalarlo directo. **Antes de distribuirlo, configurá tu propio keystore.**

## Release

Cada tag `v*` compila los APKs firmados y crea un Release en GitHub (`.github/workflows/release.yml`).

### 1. Crear tu keystore (una sola vez, en tu PC)

**Guardalo bien.** Si lo perdés, las versiones nuevas ya no se pueden instalar encima de las viejas.

En PowerShell (`keytool` viene con Android Studio, en `C:\Program Files\Android\Android Studio\jbr\bin`):

```powershell
keytool -genkeypair -keystore spotifast.jks -alias spotifast -keyalg RSA -keysize 2048 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("spotifast.jks")) | Set-Clipboard
```

### 2. Cargar los secrets en GitHub

Repo → Settings → Secrets and variables → Actions → **New repository secret**:

| Secret | Valor |
|---|---|
| `SPOTIFAST_KEYSTORE_BASE64` | El texto que quedó en el portapapeles |
| `SPOTIFAST_KEYSTORE_PASSWORD` | La contraseña del keystore |
| `SPOTIFAST_KEY_ALIAS` | `spotifast` |
| `SPOTIFAST_KEY_PASSWORD` | La contraseña de la clave (si es la misma, repetila) |

### 3. Publicar

```bash
git tag v0.6.0
git push origin v0.6.0
```

O desde GitHub: Actions → **Release** → Run workflow → versión `0.6.0`.

El Release queda con un APK por procesador, el universal y `SHA256SUMS.txt`. La versión sale del tag (`v0.6.0` → versionCode 600).

## Limitaciones conocidas (honestas)

- Probado en un emulador; falta validarlo en más teléfonos reales, sobre todo con Android 4.4.
- librespot no es oficial. Spotify cambió su protocolo varias veces y a veces lo rompe temporalmente.
- Sin app propia, la búsqueda depende del client ID compartido y Spotify la limita (429). Ver "Búsqueda y biblioteca".
- "Agregar a la cola" usa la Web API (librespot no lo expone): siempre agrega al final, no hay "reproducir a continuación" ni quitar de la cola.
- Sin modo sin conexión (como Spotifast): la precarga ahorra datos pero sigue necesitando señal.

## Nombre y marca

Tinyl (tiny + vinyl) es un nombre propio para no usar "Spotify" ni "Spoti-", que la marca de Spotify no permite a apps de terceros. Tinyl no está afiliado a Spotify AB; "Spotify" se usa solo para describir con qué funciona.

No afiliado a Spotify.
