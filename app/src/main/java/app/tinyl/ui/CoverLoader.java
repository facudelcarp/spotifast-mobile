package app.tinyl.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import app.tinyl.engine.NativeEngine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carga mínima de carátulas (solo si el modo ahorro está apagado). Caché en memoria de ~4 MB. */
public final class CoverLoader {

    public interface Cb {
        void onBitmap(Bitmap b);
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CoverLoader() {
    }

    /**
     * Tono de fondo para el mini reproductor: promedio de la miniatura (64 px, 64 muestras),
     * oscurecido para que el texto blanco siempre se lea. Cuesta microsegundos.
     */
    public static int tone(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = h / 16; y < h; y += Math.max(1, h / 8)) {
            for (int x = w / 16; x < w; x += Math.max(1, w / 8)) {
                int c = b.getPixel(x, y);
                r += (c >> 16) & 0xff;
                g += (c >> 8) & 0xff;
                bl += c & 0xff;
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        // Oscurecer al ~45% y saturar un poco hacia el color dominante.
        int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (bl / n);
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(rr, gg, bb, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.25f);
        hsv[2] = Math.min(0.42f, Math.max(0.22f, hsv[2] * 0.55f));
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** Modo demo: carátula dibujada en el teléfono. */
    static void put(String url, Bitmap b) {
        CACHE.put(url, b);
    }

    public static Bitmap cached(String url) {
        return url == null ? null : CACHE.get(url);
    }

    public static void into(final ImageView view, final String url) {
        view.setTag(url);
        Bitmap hit = url == null ? null : CACHE.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null); // fila reciclada: no mostrar la imagen anterior
        if (url == null) {
            return;
        }
        load(url, new Cb() {
            @Override
            public void onBitmap(Bitmap b) {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(b);
                }
            }
        });
    }

    public static void load(final String url, final Cb cb) {
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            cb.onBitmap(hit);
            return;
        }
        // Descarga por el motor (TLS propio) y decodificación fuera del hilo principal.
        NativeEngine.http("GET", url, null, null, new NativeEngine.HttpCallback() {
            @Override
            public void onResponse(int status, final byte[] body, String retryAfter) {
                if (status != 200 || body == null) {
                    return;
                }
                IO.execute(new Runnable() {
                    @Override
                    public void run() {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inPreferredConfig = Bitmap.Config.RGB_565; // mitad de memoria
                        final Bitmap b = BitmapFactory.decodeByteArray(body, 0, body.length, o);
                        if (b == null) {
                            return;
                        }
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                CACHE.put(url, b);
                                cb.onBitmap(b);
                            }
                        });
                    }
                });
            }
        });
    }
}
