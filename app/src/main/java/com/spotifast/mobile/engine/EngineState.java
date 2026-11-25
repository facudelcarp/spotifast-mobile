package com.spotifast.mobile.engine;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Estado de reproducción armado a partir de los eventos del motor (protocolo Soloist).
 * Único por proceso; lo comparten la UI y el servicio. Solo se toca en el hilo principal.
 */
public final class EngineState implements NativeEngine.Listener {

    public interface Observer {
        void onStateChanged(EngineState s);
    }

    private static EngineState instance;

    public static EngineState get() {
        if (instance == null) {
            instance = new EngineState();
            NativeEngine.addListener(instance);
        }
        return instance;
    }

    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();

    public boolean running;
    public boolean loggedIn;
    public boolean active;
    public String username = "";
    public String deviceName = "";
    public String status = "idle"; // idle | playing | paused | buffering
    public String uri = "";
    public String title = "";
    public String artist = "";
    public String album = "";
    public String coverSmall;
    public String coverDefault;
    public long durationMs;
    public int volume = 50;
    public boolean shuffle;
    public String repeat = "off";
    public String lastError;
    /** true mientras se espera que otra app de Spotify elija este teléfono (login alternativo). */
    public boolean pairing;

    private long positionMs;
    private long anchorElapsed; // SystemClock.elapsedRealtime() del último position_sync
    private float speed;

    private EngineState() {
    }

    public void addObserver(Observer o) {
        observers.addIfAbsent(o);
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    public boolean isPlaying() {
        return "playing".equals(status);
    }

    /** Posición interpolada localmente: sin consultas periódicas al motor. */
    public long positionNow() {
        long p = positionMs + (long) ((SystemClock.elapsedRealtime() - anchorElapsed) * speed);
        if (durationMs > 0 && p > durationMs) {
            p = durationMs;
        }
        return Math.max(0, p);
    }

    public void markPairing() {
        pairing = true;
        lastError = null;
        notifyObservers();
    }

    public void markStarting() {
        running = true;
        lastError = null;
        notifyObservers();
    }

    @Override
    public void onEngineEvent(String type, JSONObject ev) {
        switch (type) {
            case "auth_state":
                loggedIn = ev.optBoolean("logged_in");
                active = ev.optBoolean("is_active");
                deviceName = ev.optString("device_name", deviceName);
                if (!loggedIn) {
                    running = false;
                }
                break;
            case "session_info":
                username = ev.optString("username");
                break;
            case "device_changed":
                active = ev.optBoolean("is_active");
                break;
            case "playback_state":
                status = ev.optString("status", status);
                parseItem(ev.optJSONObject("item"));
                parsePosition(ev.optJSONObject("position"));
                volume = ev.optInt("volume", volume);
                active = ev.optBoolean("is_active", active);
                parseOptions(ev.optJSONObject("options"));
                break;
            case "playback_changed":
                status = ev.optString("status", status);
                if (!"playing".equals(status)) {
                    // congelar la posición
                    positionMs = positionNow();
                    anchorElapsed = SystemClock.elapsedRealtime();
                    speed = 0f;
                }
                break;
            case "position_sync":
                parsePosition(ev.optJSONObject("position"));
                break;
            case "track_changed":
                parseItem(ev.optJSONObject("item"));
                break;
            case "volume_changed":
                volume = ev.optInt("volume", volume);
                break;
            case "options_changed":
                parseOptions(ev.optJSONObject("options"));
                break;
            case "engine_error":
                running = false;
                loggedIn = false;
                lastError = ev.optString("message");
                break;
            case "pairing":
                String st = ev.optString("state");
                pairing = "waiting".equals(st);
                if ("done".equals(st)) {
                    running = true; // el motor arranca con las credenciales recibidas
                } else if ("error".equals(st)) {
                    lastError = ev.optString("message");
                }
                break;
            case "error":
                lastError = ev.optString("message");
                break;
            default:
                return; // command_result, token, etc.: no cambian el estado visible
        }
        notifyObservers();
    }

    private void parsePosition(JSONObject p) {
        if (p == null) {
            return;
        }
        positionMs = p.optLong("position_ms");
        speed = (float) p.optDouble("speed", 0);
        // El motor corre en este mismo teléfono: convertimos el timestamp de pared a reloj
        // monotónico para que no lo afecten cambios de hora.
        long wallTs = p.optLong("timestamp_ms", System.currentTimeMillis());
        anchorElapsed = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wallTs);
    }

    private void parseOptions(JSONObject o) {
        if (o == null) {
            return;
        }
        shuffle = o.optBoolean("shuffle", shuffle);
        repeat = o.optString("repeat", repeat);
    }

    private void parseItem(JSONObject item) {
        if (item == null) {
            return;
        }
        uri = item.optString("uri");
        JSONObject d = item.optJSONObject("decorations");
        if (d == null) {
            return;
        }
        title = name(d);
        JSONObject parent = d.optJSONObject("parent");
        album = parent == null ? "" : name(parent.optJSONObject("entity"));
        StringBuilder sb = new StringBuilder();
        JSONArray creators = d.optJSONArray("creators");
        if (creators != null) {
            for (int i = 0; i < creators.length(); i++) {
                JSONObject c = creators.optJSONObject(i);
                String n = c == null ? "" : name(c.optJSONObject("entity"));
                if (n.length() > 0) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(n);
                }
            }
        }
        artist = sb.toString();
        JSONObject pb = d.optJSONObject("playback");
        durationMs = pb == null ? 0 : pb.optLong("duration_ms");
        coverSmall = null;
        coverDefault = null;
        JSONObject vi = d.optJSONObject("visual_identity");
        JSONArray covers = vi == null ? null : vi.optJSONArray("cover");
        if (covers != null) {
            for (int i = 0; i < covers.length(); i++) {
                JSONObject c = covers.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                String size = c.optString("size");
                if ("small".equals(size)) {
                    coverSmall = c.optString("url");
                } else if ("default".equals(size)) {
                    coverDefault = c.optString("url");
                }
            }
        }
        if (coverSmall == null) {
            coverSmall = coverDefault;
        }
    }

    private static String name(JSONObject withDecorations) {
        if (withDecorations == null) {
            return "";
        }
        JSONObject d = withDecorations.has("decorations")
                ? withDecorations.optJSONObject("decorations") : withDecorations;
        JSONObject id = d == null ? null : d.optJSONObject("identity");
        return id == null ? "" : id.optString("name");
    }

    private void notifyObservers() {
        for (Observer o : observers) {
            o.onStateChanged(this);
        }
    }
}
