package app.tinyl.media;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;

import app.tinyl.MediaButtonReceiver;
import app.tinyl.engine.EngineState;

/** Android 4.4: RemoteControlClient (controles en la pantalla bloqueada) + botones de auriculares. */
@SuppressWarnings("deprecation")
final class LegacyControls extends MediaControls {

    private final AudioManager am;
    private final ComponentName receiver;
    private final RemoteControlClient rcc;

    @SuppressLint("UnspecifiedImmutableFlag") // solo corre en API < 21, donde el flag no existe
    LegacyControls(Context c) {
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        receiver = new ComponentName(c, MediaButtonReceiver.class);
        am.registerMediaButtonEventReceiver(receiver);
        Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver);
        rcc = new RemoteControlClient(PendingIntent.getBroadcast(c, 0, i, 0));
        rcc.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
        am.registerRemoteControlClient(rcc);
    }

    @Override
    public void update(EngineState s, Bitmap art, boolean metadataChanged) {
        int st;
        switch (s.status) {
            case "playing":
                st = RemoteControlClient.PLAYSTATE_PLAYING;
                break;
            case "paused":
                st = RemoteControlClient.PLAYSTATE_PAUSED;
                break;
            case "buffering":
                st = RemoteControlClient.PLAYSTATE_BUFFERING;
                break;
            default:
                st = RemoteControlClient.PLAYSTATE_STOPPED;
        }
        rcc.setPlaybackState(st);
        if (metadataChanged) {
            RemoteControlClient.MetadataEditor e = rcc.editMetadata(true)
                    .putString(MediaMetadataRetriever.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, s.album)
                    .putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, s.durationMs);
            if (art != null) {
                e.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, art);
            }
            e.apply();
        }
    }

    @Override
    public void decorate(Notification.Builder b) {
        // Sin MediaStyle en 4.4: la notificación usa los botones normales (addAction).
    }

    @Override
    public void release() {
        am.unregisterRemoteControlClient(rcc);
        am.unregisterMediaButtonEventReceiver(receiver);
    }
}
