package app.tinyl.web;

import android.content.Context;
import android.content.SharedPreferences;

import app.tinyl.SpotifyAuth;
import app.tinyl.engine.NativeEngine;

import org.json.JSONObject;

/**
 * Tokens OAuth para la Web API, uno por autorización (como Spotifast):
 * {@link #PERSONAL} = app propia del usuario (cupo propio de pedidos) y
 * {@link #SHARED} = el del login de reproducción (client ID compartido, Spotify lo limita).
 * Cada uno se renueva solo con su refresh_token y su propio client ID.
 */
public final class OAuthTokens {

    public interface Cb {
        /** {@code token} es null si no hay token utilizable de ese tipo. */
        void onToken(String token);
    }

    public static final String PERSONAL = "personal";
    public static final String SHARED = "shared";

    private OAuthTokens() {
    }

    private static SharedPreferences prefs(Context c, String kind) {
        return c.getApplicationContext().getSharedPreferences("oauth_" + kind, Context.MODE_PRIVATE);
    }

    public static boolean has(Context c, String kind) {
        return prefs(c, kind).getString("refresh", null) != null;
    }

    /** Guarda la respuesta de /api/token (canje de código o renovación). */
    public static void save(Context c, String kind, String clientId, JSONObject resp) {
        SharedPreferences.Editor e = prefs(c, kind).edit();
        e.putString("client_id", clientId);
        e.putString("access", resp.optString("access_token"));
        e.putLong("expiry", System.currentTimeMillis() + (resp.optLong("expires_in", 3600) - 60) * 1000);
        String refresh = resp.optString("refresh_token", "");
        if (refresh.length() > 0) { // en una renovación Spotify puede no mandar uno nuevo
            e.putString("refresh", refresh);
        }
        e.apply();
    }

    /** El access token venció antes de tiempo (401): se renueva en el próximo pedido. */
    public static void invalidate(Context c, String kind) {
        prefs(c, kind).edit().remove("access").apply();
    }

    public static void clear(Context c, String kind) {
        prefs(c, kind).edit().clear().apply();
    }

    public static void clearAll(Context c) {
        clear(c, PERSONAL);
        clear(c, SHARED);
    }

    public static void get(final Context c, final String kind, final Cb cb) {
        SharedPreferences p = prefs(c, kind);
        String access = p.getString("access", null);
        if (access != null && System.currentTimeMillis() < p.getLong("expiry", 0)) {
            cb.onToken(access);
            return;
        }
        String refresh = p.getString("refresh", null);
        final String clientId = p.getString("client_id", SpotifyAuth.CLIENT_ID);
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
                + "&client_id=" + clientId;
        NativeEngine.http("POST", "https://accounts.spotify.com/api/token", headers, body,
                new NativeEngine.HttpCallback() {
                    @Override
                    public void onResponse(int status, byte[] data, String retryAfter) {
                        try {
                            if (status == 200) {
                                JSONObject o = new JSONObject(new String(data, "UTF-8"));
                                save(c, kind, clientId, o);
                                cb.onToken(o.getString("access_token"));
                                return;
                            }
                            if (status == 400 || status == 401) {
                                clear(c, kind); // refresh_token revocado
                            }
                        } catch (Exception ignored) {
                        }
                        cb.onToken(null);
                    }
                });
    }
}
