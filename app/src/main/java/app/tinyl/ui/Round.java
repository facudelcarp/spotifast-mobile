package app.tinyl.ui;

import android.annotation.TargetApi;
import android.graphics.Outline;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Esquinas redondeadas y círculos recortados por la GPU (Android 5+): sin copiar bitmaps, así
 * no cuesta memoria. En Android 4.4 las imágenes quedan cuadradas, que es lo más liviano.
 */
final class Round {

    private Round() {
    }

    /** @param radiusPx radio de las esquinas; negativo = círculo. */
    static void clip(View v, float radiusPx) {
        if (Build.VERSION.SDK_INT >= 21) {
            Impl.clip(v, radiusPx);
        }
    }

    @TargetApi(21)
    private static final class Impl {
        static void clip(View v, final float r) {
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline o) {
                    if (r < 0) {
                        o.setOval(0, 0, view.getWidth(), view.getHeight());
                    } else {
                        o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                    }
                }
            });
            v.setClipToOutline(true);
        }
    }
}
