# Changelog

## v0.7.0

**Cola y reproducción automática**
- **Reproducción automática** (activada por defecto): al terminar la canción, el álbum o la playlist,
  sigue con canciones similares, como en la app oficial. Es el autoplay de Spotify (lo resuelve el
  motor directo con Spotify, sin pedidos extra a la Web API). Se apaga en Ajustes o desde la Cola.
- Una canción suelta (búsqueda, enlace compartido) ahora se reproduce como en la app oficial: al
  terminar sigue con similares en vez de detenerse.
- **Iniciar radio** desde el menú de cualquier canción (mantener apretado) o desde la Cola: esa
  canción y después similares, aunque la reproducción automática esté apagada.
- **Sonando rediseñada**: fondo teñido con el color de la carátula, "Reproduciendo desde" con el
  nombre de la playlist/álbum/radio, carátula más grande (el display LCD pasó a ser el tiempo verde
  bajo la barra), deslizar la carátula para pasar de tema, fila con el dispositivo donde suena,
  temporizador con minutos restantes, compartir enlace y menú ⋮ (radio, agregar a playlist, ir al
  álbum/artista). Títulos largos en marquesina y logo de Tinyl si no hay carátula.
- **Cola renovada**: interruptor de reproducción automática, tocar una canción salta directo a ella
  sin perder el resto, y se actualiza sola al cambiar de tema mientras está abierta.

## v0.6.0

Primera versión publicada, como **Tinyl · cliente liviano para Spotify** (antes "Spotifast Mobile").

**Reproducción**
- Motor librespot nativo dentro del APK: sin PC, sin la app oficial y sin apps auxiliares.
- Control con el mismo protocolo que la API de Spotify Soloist.
- 96/160/320 kbps, caché de audio de 512 MB, controles en notificación, pantalla bloqueada y auriculares.
- **Precargar** playlists, álbumes y Tus me gusta con Wi-Fi: después, con datos móviles, el audio sale
  del teléfono y solo viaja la clave de cada canción (sigue necesitando conexión; no es modo offline).

**Navegación (inspirada en la app oficial, liviana para gama baja)**
- Barra inferior Inicio · Buscar · Tu biblioteca · Crear.
- Inicio con accesos a lo último que escuchaste y canciones recientes.
- Búsqueda con historial local; enlaces de Spotify pegados o compartidos.
- Biblioteca con filtros (Playlists, Álbumes, Artistas) y Tus me gusta.
- Playlist, álbum y artista con Reproducir y Aleatorio; tocar una canción sigue con el resto.
- Cola, Me gusta, crear playlist y agregar canciones a una playlist.
- Mini reproductor teñido con el color de la carátula y pantalla Sonando con display LCD.

**Letras, Connect, temporizador y podcasts**
- Letras sincronizadas de Spotify con respaldo de LRCLIB; letra completa que sigue la canción.
- Elegir dispositivo Spotify Connect y traer la música de vuelta.
- Temporizador para dormir con fundido.
- Podcasts: biblioteca, episodios con progreso, "Seguir escuchando" y retomar donde quedaste.
- Inicio rediseñado: avatar, filtros, artistas del momento, grilla de 8 puntos y 4 tamaños de letra.

**Compatibilidad y consumo**
- Android 4.4 en adelante; ARM y x86, 32 y 64 bits.
- ~34 MB de RAM en uso; APK de ~3 MB por procesador.
- Login por navegador o desde otro dispositivo (Spotify Connect) para equipos viejos.
- Web API con app propia del usuario (sin 429 del client ID compartido).
- Logo propio (vinilo chiquito con ▶), ícono adaptativo y temático, y marca Tinyl en la notificación
  y la pantalla bloqueada cuando no hay carátula.
