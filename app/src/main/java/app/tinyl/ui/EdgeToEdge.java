package app.tinyl.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

/**
 * Zonas seguras: la app se dibuja de borde a borde (en Android 15 es obligatorio) y corre su
 * contenido lo justo para no quedar debajo de la barra de estado, el notch, la barra de
 * navegación o el teclado. Los fondos (el verde del Inicio, Sonando) siguen detrás de las barras.
 * En Android 4.4 no hace nada: ahí las barras nunca tapan la app.
 */
final class EdgeToEdge {

    private EdgeToEdge() {
    }

    /**
     * @param root    vista raíz que recibe los insets
     * @param targets vistas a las que se les suma el inset como padding (conservan su padding propio)
     */
    static void apply(Activity a, View root, View... targets) {
        if (Build.VERSION.SDK_INT >= 21) {
            Impl.apply(a, root, targets);
        }
    }

    @TargetApi(21)
    private static final class Impl {

        @SuppressWarnings("deprecation")
        static void apply(Activity a, View root, final View[] targets) {
            Window w = a.getWindow();
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setNavigationBarContrastEnforced(false); // sin velo gris sobre nuestra barra negra
            }
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

            final int[][] base = new int[targets.length][];
            for (int i = 0; i < targets.length; i++) {
                View t = targets[i];
                base[i] = new int[]{t.getPaddingLeft(), t.getPaddingTop(), t.getPaddingRight(), t.getPaddingBottom()};
            }
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                    int[] s = safe(ins);
                    for (int i = 0; i < targets.length; i++) {
                        int[] b = base[i];
                        targets[i].setPadding(b[0] + s[0], b[1] + s[1], b[2] + s[2], b[3] + s[3]);
                    }
                    return ins;
                }
            });
            root.requestApplyInsets();
        }

        /** {izq, arriba, der, abajo}: barras del sistema + notch + teclado. */
        @SuppressWarnings("deprecation")
        static int[] safe(WindowInsets ins) {
            if (Build.VERSION.SDK_INT >= 30) {
                return Api30.safe(ins);
            }
            int l = ins.getSystemWindowInsetLeft(), t = ins.getSystemWindowInsetTop();
            int r = ins.getSystemWindowInsetRight(), b = ins.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28 && ins.getDisplayCutout() != null) {
                t = Math.max(t, ins.getDisplayCutout().getSafeInsetTop());
                l = Math.max(l, ins.getDisplayCutout().getSafeInsetLeft());
                r = Math.max(r, ins.getDisplayCutout().getSafeInsetRight());
            }
            return new int[]{l, t, r, b};
        }
    }

    @TargetApi(30)
    private static final class Api30 {
        static int[] safe(WindowInsets ins) {
            android.graphics.Insets bars = ins.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = ins.getInsets(WindowInsets.Type.ime());
            return new int[]{bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom)};
        }
    }
}
