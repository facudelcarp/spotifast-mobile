package app.tinyl.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Puente con el motor nativo (librespot en Rust, ver /native).
 *
 * El protocolo es el mismo de la API WebSocket de Spotify Soloist: se mandan comandos
 * {"type":"command","command":"..."} y se reciben eventos JSON con un campo "type".
 * Los eventos se entregan siempre en el hilo principal.
 */
public final class NativeEngine {

    public interface Listener {
        void onEngineEvent(String type, JSONObject event);
    }

    /**
     * Respuesta HTTP. {@code status} 0 = error de red (el cuerpo trae el mensaje).
     * {@code retryAfter}: segundos que pide Spotify esperar tras un 429 ("" si no vino).
     */
    public interface HttpCallback {
        void onResponse(int status, byte[] body, String retryAfter);
    }

    private static final String TAG = "tinyl";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile AudioOut audio;
    private static final SparseArray<HttpCallback> HTTP = new SparseArray<>();
    private static int nextHttpId = 1;

    static {
        System.loadLibrary("tinyl");
        nativeInit();
    }

    private NativeEngine() {
    }

    // ---- nativo
    private static native void nativeInit();

    private static native boolean nativeHasCredentials(String dataDir);

    private static native void nativeStart(String dataDir, String cacheDir, String deviceName,
                                           String deviceId, String accessToken, int bitrate,
                                           int volume, int cacheMb);

    private static native void nativeStop();

    private static native void nativeCommand(String json);

    private static native void nativeRequestToken(String scopes, int reqId);

    private static native void nativeStartPairing(String dataDir, String cacheDir, String deviceName,
                                                  String deviceId, String wifiIp, int bitrate,
                                                  int volume, int cacheMb);

    private static native void nativeStopPairing();

    private static native void nativePrefetch(String urisJson, int bitrate, int reqId);

    private static native void nativePrefetchCancel();

    private static native void nativeHttp(String method, String url, String headersJson,
                                          String body, int reqId);

    // ---- API Java
    public static boolean hasCredentials(String dataDir) {
        return nativeHasCredentials(dataDir);
    }

    public static void start(String dataDir, String cacheDir, String deviceName, String deviceId,
                             String accessToken, int bitrate, int volume, int cacheMb) {
        nativeStart(dataDir, cacheDir, deviceName, deviceId,
                accessToken == null ? "" : accessToken, bitrate, volume, cacheMb);
    }

    public static void stop() {
        nativeStop();
    }

    public static void requestToken(String scopes, int reqId) {
        nativeRequestToken(scopes, reqId);
    }

    /** Login alternativo: esperar a que otra app de Spotify en la misma Wi-Fi elija este equipo. */
    public static void startPairing(String dataDir, String cacheDir, String deviceName, String deviceId,
                                    String wifiIp, int bitrate, int volume, int cacheMb) {
        nativeStartPairing(dataDir, cacheDir, deviceName, deviceId, wifiIp == null ? "" : wifiIp,
                bitrate, volume, cacheMb);
    }

    public static void stopPairing() {
        nativeStopPairing();
    }

    /** Precarga a la caché (cifrada) los archivos de audio de estas pistas. Ver native/src/prefetch.rs. */
    public static void prefetch(String urisJson, int bitrate, int reqId) {
        nativePrefetch(urisJson, bitrate, reqId);
    }

    public static void prefetchCancel() {
        nativePrefetchCancel();
    }

    /**
     * HTTPS hecho por el motor (rustls con certificados propios): funciona igual en Android 4.4,
     * que trae TLS 1.2 apagado y certificados raíz viejos. El callback llega en el hilo principal.
     */
    public static void http(String method, String url, JSONObject headers, String body, HttpCallback cb) {
        int id;
        synchronized (HTTP) {
            id = nextHttpId++;
            HTTP.put(id, cb);
        }
        nativeHttp(method, url, headers == null ? "{}" : headers.toString(), body == null ? "" : body, id);
    }

    /** Envía un comando Soloist. {@code kv} son pares clave/valor opcionales. */
    public static void command(String name, Object... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "command");
            o.put("command", name);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        } catch (JSONException e) {
            return;
        }
        nativeCommand(o.toString());
    }

    public static void setAudioOut(AudioOut out) {
        audio = out;
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    // ---- llamados desde Rust (no renombrar: ver proguard-rules.pro)
    static void onEvent(String json) {
        final JSONObject ev;
        try {
            ev = new JSONObject(json);
        } catch (JSONException e) {
            Log.w(TAG, "evento inválido: " + json);
            return;
        }
        final String type = ev.optString("type");
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) {
                    l.onEngineEvent(type, ev);
                }
            }
        });
    }

    static void onHttp(int reqId, final int status, final byte[] body, final String retryAfter) {
        final HttpCallback cb;
        synchronized (HTTP) {
            cb = HTTP.get(reqId);
            HTTP.remove(reqId);
        }
        if (cb == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onResponse(status, body, retryAfter);
            }
        });
    }

    static void onPcm(short[] data, int length) {
        AudioOut a = audio;
        if (a != null) {
            a.write(data, length);
        }
    }

    static void onAudioStart() {
        AudioOut a = audio;
        if (a != null) {
            a.start();
        }
    }

    static void onAudioStop() {
        AudioOut a = audio;
        if (a != null) {
            a.stop();
        }
    }
}
