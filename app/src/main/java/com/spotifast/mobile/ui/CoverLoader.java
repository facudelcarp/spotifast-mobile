package com.spotifast.mobile.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.spotifast.mobile.engine.NativeEngine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carga mínima de carátulas (solo si el modo ahorro está apagado). Caché en memoria de ~2 MB. */
public final class CoverLoader {

    public interface Cb {
        void onBitmap(Bitmap b);
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(2 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CoverLoader() {
    }

    public static Bitmap cached(String url) {
        return url == null ? null : CACHE.get(url);
    }

    public static void into(final ImageView view, final String url) {
        view.setTag(url);
        if (url == null) {
            view.setImageDrawable(null);
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
            public void onResponse(int status, final byte[] body) {
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
