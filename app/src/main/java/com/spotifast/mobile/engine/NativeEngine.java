package com.spotifast.mobile.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

    private static final String TAG = "spotifast";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile AudioOut audio;

    static {
        System.loadLibrary("spotifast");
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
