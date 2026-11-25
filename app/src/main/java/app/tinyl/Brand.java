package app.tinyl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/** Logo de Tinyl como Bitmap (notificación, pantalla bloqueada, mini reproductor sin carátula). */
public final class Brand {

    private static Bitmap logo;

    private Brand() {
    }

    /** El ícono de la app dibujado a {@code px} (se cachea: es uno solo y chico). */
    @SuppressWarnings("deprecation")
    public static synchronized Bitmap logo(Context c, int px) {
        if (logo == null || logo.getWidth() != px) {
            // En Android 4.4 el vector ya viene convertido a PNG por el build: getDrawable sirve igual.
            Drawable d = c.getResources().getDrawable(R.drawable.ic_launcher);
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            d.setBounds(0, 0, px, px);
            d.draw(canvas);
            logo = b;
        }
        return logo;
    }
}
