package com.spotifast.mobile.web;

import android.content.Context;
import android.content.SharedPreferences;

import com.spotifast.mobile.SpotifyAuth;
import com.spotifast.mobile.engine.NativeEngine;

import org.json.JSONObject;

/**
 * Token OAuth del login por navegador, guardado para la Web API (búsqueda y biblioteca).
 * Tiene los permisos pedidos en el login y se renueva solo con el refresh_token, sin volver
 * a abrir el navegador. Los que entraron por Spotify Connect no lo tienen: usan el del motor.
 */
public final class OAuthTokens {

    public interface Cb {
        /** {@code token} es null si no hay token OAuth utilizable. */
        void onToken(String token);
    }

    private static final String FILE = "oauth";

    private OAuthTokens() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Guarda la respuesta de /api/token (canje de código o renovación). */
    public static void save(Context c, JSONObject resp) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString("access", resp.optString("access_token"));
        e.putLong("expiry", System.currentTimeMillis() + (resp.optLong("expires_in", 3600) - 60) * 1000);
        String refresh = resp.optString("refresh_token", "");
        if (refresh.length() > 0) { // en una renovación Spotify puede no mandar uno nuevo
            e.putString("refresh", refresh);
        }
        e.apply();
    }

    /** El access token venció antes de tiempo (401): se renueva en el próximo pedido. */
    public static void invalidate(Context c) {
        prefs(c).edit().remove("access").apply();
    }

    public static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    public static void get(final Context c, final Cb cb) {
        SharedPreferences p = prefs(c);
        String access = p.getString("access", null);
        if (access != null && System.currentTimeMillis() < p.getLong("expiry", 0)) {
            cb.onToken(access);
            return;
        }
        String refresh = p.getString("refresh", null);
        if (refresh == null) {
            cb.onToken(null);
            return;
        }
        JSONObject headers = new JSONObject();
        try {
            headers.put("Content-Type", "application/x-www-form-urlencoded");
        } catch (Exception ignored) {
        }
        String body = "grant_type=refresh_token&refresh_token=" + SpotifyAuth.enc(refresh)
                + "&client_id=" + SpotifyAuth.CLIENT_ID;
        NativeEngine.http("POST", "https://accounts.spotify.com/api/token", headers, body,
                new NativeEngine.HttpCallback() {
                    @Override
                    public void onResponse(int status, byte[] data) {
                        try {
                            if (status == 200) {
                                JSONObject o = new JSONObject(new String(data, "UTF-8"));
                                save(c, o);
                                cb.onToken(o.getString("access_token"));
                                return;
                            }
                            if (status == 400 || status == 401) {
                                clear(c); // refresh_token revocado
                            }
                        } catch (Exception ignored) {
                        }
                        cb.onToken(null);
                    }
                });
    }
}
