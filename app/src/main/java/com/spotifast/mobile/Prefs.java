package com.spotifast.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/** Preferencias de la app. Los valores por defecto priorizan el bajo consumo. */
public final class Prefs {

    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getSharedPreferences("spotifast", Context.MODE_PRIVATE);
    }

    public String deviceName() {
        return p.getString("device_name", "Spotifast " + Build.MODEL);
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

    /** Modo ahorro: sin carátulas ni animaciones. */
    public boolean eco() {
        return p.getBoolean("eco", true);
    }

    public void setEco(boolean v) {
        p.edit().putBoolean("eco", v).apply();
    }

    /** Caché de audio: repetir canciones no gasta datos. */
    public int cacheMb() {
        return p.getInt("cache_mb", 200);
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

    public int volume() {
        return p.getInt("volume", 70);
    }

    public void setVolume(int v) {
        p.edit().putInt("volume", v).apply();
    }
}
