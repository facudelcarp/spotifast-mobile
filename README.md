# Spotifast Mobile

Cliente de Spotify **liviano** para Android, inspirado en [Spotifast](https://github.com/crmne/spotifast).
Corre **solo en el teléfono**: sin PC, sin la app oficial y sin apps auxiliares. APK de unos 3 MB.

- Android **5.0 (API 21) en adelante**, incluidos teléfonos de 32 bits (armeabi-v7a).
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

## Arquitectura

```
┌──────────────────────── APK ────────────────────────┐
│ MainActivity (UI estilo LCD)                        │
│   │ comandos Soloist (JSON)        ▲ eventos Soloist│
│   ▼                                │                │
│ NativeEngine (JNI) ───────────────►┘                │
│   │                                                 │
│   ▼  libspotifast.so (Rust)                         │
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
- **Caché de audio (200 MB)**: escuchar de nuevo una canción no gasta datos.
- **Buffer de audio de ~0,5 s** y conversión sin dither: el procesador se despierta menos.
- **Locks solo mientras suena.** El wakelock y el wifilock se sueltan al pausar. Tras 10 minutos en pausa con la app cerrada, el motor se apaga.
- **Modo ahorro activado por defecto**: sin carátulas. Tema **negro puro**, que gasta menos en pantallas OLED.
- **Sin AndroidX, sin Kotlin y sin descubrimiento mDNS.** APK chico, arranque rápido y nada escuchando la red de fondo.
- **Un APK por arquitectura**: cada teléfono baja solo su motor.

## Uso

1. Instalá el APK que corresponda a tu teléfono. Si no sabés cuál, usá `app-universal-release.apk`.
2. Tocá **Iniciar sesión con Spotify**. Se abre el navegador una sola vez y la sesión queda guardada.
3. Buscá, abrí la biblioteca o **compartí cualquier enlace de Spotify** a Spotifast para reproducirlo.
4. El teléfono también aparece como dispositivo Spotify Connect en tus otras apps de Spotify.

## Compilar

Requisitos: JDK 17+, Android SDK (API 35) y NDK 27.2.12479018, Rust con `cargo-ndk`.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
./gradlew assembleRelease          # compila también el motor Rust (tarea cargoBuild)
./gradlew assembleRelease -PskipRust   # si las .so ya están en app/src/main/jniLibs
```

Los APK quedan en `app/build/outputs/apk/release/`. El CI (`.github/workflows/android.yml`) los publica como artefacto en cada push.
El APK de release se firma con la clave de debug para poder instalarlo directo. **Antes de distribuirlo, configurá tu propio keystore.**

## Limitaciones conocidas (honestas)

- **No está probado en un teléfono real todavía.** Compila para las tres arquitecturas y lint no reporta APIs fuera de Android 5, pero la sesión, el audio y el login hay que validarlos en un equipo.
- librespot no es oficial. Spotify cambió su protocolo varias veces y a veces lo rompe temporalmente.
- La búsqueda usa la Web API con el token de la sesión de librespot. Si Spotify restringe ese acceso, la reproducción sigue funcionando (enlaces compartidos y Connect), pero la búsqueda no.
- "Agregar a la cola" todavía no está implementado: el motor no lo expone.
- Sin descargas offline (como Spotifast).

No afiliado a Spotify.
