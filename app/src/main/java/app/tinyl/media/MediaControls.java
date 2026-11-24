package app.tinyl.media;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

import app.tinyl.engine.EngineState;

/**
 * Controles del sistema (pantalla bloqueada, auriculares, notificación).
 * Android 5+ usa MediaSession; Android 4.4 usa RemoteControlClient. Cada implementación vive
 * en su propia clase para que la VM vieja nunca cargue clases que no existen en ella.
 */
public abstract class MediaControls {

    public static MediaControls create(Context c) {
        return Build.VERSION.SDK_INT >= 21 ? new SessionControls(c) : new LegacyControls(c);
    }

    public abstract void update(EngineState s, Bitmap art, boolean metadataChanged);

    /** Agrega el estilo de notificación multimedia si la plataforma lo soporta. */
    public abstract void decorate(Notification.Builder b);

    public abstract void release();
}
