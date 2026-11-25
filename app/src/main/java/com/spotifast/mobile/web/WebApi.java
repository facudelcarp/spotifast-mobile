package com.spotifast.mobile.web;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.spotifast.mobile.engine.NativeEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Búsqueda y biblioteca vía Spotify Web API. El token lo entrega el motor (sesión de librespot),
 * así no hace falta registrar una app propia ni un segundo login.
 * Solo se llama cuando el usuario busca o abre la biblioteca: cero tráfico en segundo plano.
 */
public final class WebApi implements NativeEngine.Listener {

    public static final class Item {
        public final String uri;
        public final String title;
        public final String subtitle;
        public final String kind;

        Item(String uri, String title, String subtitle, String kind) {
            this.uri = uri;
            this.title = title;
            this.subtitle = subtitle;
            this.kind = kind;
        }
    }

    public interface Result {
        void onItems(List<Item> items);

        void onError(String message);
    }

    private interface TokenCb {
        void onToken(String token, String error);
    }

    private static final String SCOPES =
            "playlist-read-private,playlist-read-collaborative,user-library-read,user-read-private";
    private static WebApi instance;

    public static WebApi get() {
        if (instance == null) {
            instance = new WebApi();
            NativeEngine.addListener(instance);
        }
        return instance;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final android.util.SparseArray<TokenCb> pending = new android.util.SparseArray<>();
    private int nextReq = 1;
    private String token;
    private long tokenExpiry;

    private WebApi() {
    }

    public void search(String query, Result r) {
        String q;
        try {
            q = URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            q = query;
        }
        get("/v1/search?type=track,album,playlist,artist&limit=8&q=" + q, r, new Parser() {
            @Override
            public void parse(JSONObject o, List<Item> out) {
                section(o.optJSONObject("tracks"), "track", out);
                section(o.optJSONObject("albums"), "album", out);
                section(o.optJSONObject("playlists"), "playlist", out);
                section(o.optJSONObject("artists"), "artist", out);
            }
        });
    }

    public void myPlaylists(final String username, Result r) {
        get("/v1/me/playlists?limit=50", r, new Parser() {
            @Override
            public void parse(JSONObject o, List<Item> out) {
                if (username != null && username.length() > 0) {
                    out.add(new Item("spotify:user:" + username + ":collection",
                            "Tus me gusta", "Biblioteca", "playlist"));
                }
                section(o, "playlist", out);
            }
        });
    }

    // ------------------------------------------------------------------

    private interface Parser {
        void parse(JSONObject o, List<Item> out) throws Exception;
    }

    private void get(final String path, final Result r, final Parser parser) {
        withToken(new TokenCb() {
            @Override
            public void onToken(final String tok, String error) {
                if (tok == null) {
                    r.onError(error == null ? "sin token" : error);
                    return;
                }
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            HttpURLConnection c = (HttpURLConnection) new URL("https://api.spotify.com" + path).openConnection();
                            c.setConnectTimeout(15000);
                            c.setReadTimeout(15000);
                            c.setRequestProperty("Authorization", "Bearer " + tok);
                            int status = c.getResponseCode();
                            String body = read(status < 400 ? c.getInputStream() : c.getErrorStream());
                            c.disconnect();
                            if (status == 401) {
                                token = null;
                            }
                            if (status >= 400) {
                                throw new Exception("Web API " + status);
                            }
                            final List<Item> out = new ArrayList<>();
                            parser.parse(new JSONObject(body), out);
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    r.onItems(out);
                                }
                            });
                        } catch (final Exception e) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    r.onError(e.getMessage() == null ? e.toString() : e.getMessage());
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void withToken(TokenCb cb) {
        if (token != null && SystemClock.elapsedRealtime() < tokenExpiry) {
            cb.onToken(token, null);
            return;
        }
        int id = nextReq++;
        pending.put(id, cb);
        NativeEngine.requestToken(SCOPES, id);
    }

    @Override
    public void onEngineEvent(String type, JSONObject ev) {
        if (!"token".equals(type)) {
            return;
        }
        int id = ev.optInt("req_id");
        TokenCb cb = pending.get(id);
        pending.remove(id);
        if (cb == null) {
            return;
        }
        String t = ev.optString("access_token", null);
        if (t != null && t.length() > 0) {
            token = t;
            long ttl = Math.max(60, ev.optLong("expires_in", 3600) - 60);
            tokenExpiry = SystemClock.elapsedRealtime() + ttl * 1000;
            cb.onToken(t, null);
        } else {
            cb.onToken(null, ev.optString("error", "sin token"));
        }
    }

    private static void section(JSONObject paging, String kind, List<Item> out) {
        if (paging == null) {
            return;
        }
        JSONArray items = paging.optJSONArray("items");
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) {
                continue; // la API a veces devuelve null en playlists
            }
            String sub;
            switch (kind) {
                case "track":
                    sub = artists(it) + " · " + optName(it.optJSONObject("album"));
                    break;
                case "album":
                    sub = "Álbum · " + artists(it);
                    break;
                case "playlist":
                    JSONObject owner = it.optJSONObject("owner");
                    sub = "Playlist" + (owner == null ? "" : " · " + owner.optString("display_name"));
                    break;
                default:
                    sub = "Artista";
            }
            out.add(new Item(it.optString("uri"), it.optString("name"), sub, kind));
        }
    }

    private static String artists(JSONObject it) {
        JSONArray a = it.optJSONArray("artists");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(optName(a.optJSONObject(i)));
        }
        return sb.toString();
    }

    private static String optName(JSONObject o) {
        return o == null ? "" : o.optString("name");
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        return sb.toString();
    }
}
