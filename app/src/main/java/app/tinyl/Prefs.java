package app.tinyl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/** Preferencias de la app. Los valores por defecto priorizan el bajo consumo. */
public final class Prefs {

    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getSharedPreferences("tinyl", Context.MODE_PRIVATE);
    }

    public String deviceName() {
        return p.getString("device_name", "Tinyl " + Build.MODEL);
    }

    public void setDeviceName(String v) {
        p.edit().putString("device_name", v).apply();
    }

    /** Identificador estable: sin él, cada arranque aparecería como un dispositivo nuevo. */
    public String deviceId() {
        String id = p.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    /** 96 / 160 / 320 kbps. Por defecto 96: menos datos móviles y menos CPU. */
    public int bitrate() {
        return p.getInt("bitrate", 96);
    }

    public void setBitrate(int v) {
        p.edit().putInt("bitrate", v).apply();
    }

    /** Modo ahorro: sin imágenes. Apagado por defecto: las miniaturas son de ~64 px (pocos KB). */
    public boolean eco() {
        return p.getBoolean("eco", false);
    }

    public void setEco(boolean v) {
        p.edit().putBoolean("eco", v).apply();
    }

    /** Caché de audio: repetir canciones no gasta datos. */
    public int cacheMb() {
        // 512 MB: alcanza para unas 25 h de audio a 96 kbps (precargas incluidas).
        return p.getInt("cache_mb", 512);
    }

    public void setCacheMb(int v) {
        p.edit().putInt("cache_mb", v).apply();
    }

    /** Minutos en pausa (sin la app abierta) antes de apagar el motor. */
    public int idleMinutes() {
        return p.getInt("idle_minutes", 10);
    }

    public void setIdleMinutes(int v) {
        p.edit().putInt("idle_minutes", v).apply();
    }

    /** Client ID de la app propia del usuario para la Web API ("" = usar la compartida). */
    public String webClientId() {
        return p.getString("web_client_id", "");
    }

    public void setWebClientId(String v) {
        p.edit().putString("web_client_id", v).apply();
    }

    /** Búsquedas recientes (máx. 10), guardadas solo en el teléfono. */
    public java.util.List<String> searchHistory() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(p.getString("search_history", "[]"));
            for (int i = 0; i < a.length(); i++) {
                out.add(a.getString(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void addSearch(String q) {
        java.util.List<String> h = searchHistory();
        h.remove(q);
        h.add(0, q);
        org.json.JSONArray a = new org.json.JSONArray();
        for (int i = 0; i < h.size() && i < 10; i++) {
            a.put(h.get(i));
        }
        p.edit().putString("search_history", a.toString()).apply();
    }

    public void clearSearches() {
        p.edit().remove("search_history").apply();
    }

    /** Precargar solo con Wi-Fi (por defecto sí: es el punto de precargar). */
    public boolean prefetchWifiOnly() {
        return p.getBoolean("prefetch_wifi_only", true);
    }

    public void setPrefetchWifiOnly(boolean v) {
        p.edit().putBoolean("prefetch_wifi_only", v).apply();
    }

    public int volume() {
        return p.getInt("volume", 70);
    }

    public void setVolume(int v) {
        p.edit().putInt("volume", v).apply();
    }
}
