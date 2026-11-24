package com.spotifast.mobile.web;

import android.os.SystemClock;

import com.spotifast.mobile.engine.NativeEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Spotify Web API (búsqueda, biblioteca, detalle, cola, Me gusta), con los endpoints que usa
 * Spotifast desde la migración de 2026: {@code /playlists/{id}/items} y {@code /me/library}.
 *
 * Solo se llama cuando el usuario hace algo: cero tráfico en segundo plano. Las listas se piden
 * de a una página y la siguiente solo si el usuario la pide ("Cargar más").
 */
public final class WebApi implements NativeEngine.Listener {

    /** Una fila: pista, episodio, álbum, playlist, artista, show o encabezado de sección. */
    public static final class Item {
        public final String uri;
        public final String title;
        public final String subtitle;
        public final String kind;
        /** Para "Ir al álbum" / "Ir al artista" (pueden ser null). */
        public String albumUri;
        public String artistUri;

        Item(String uri, String title, String subtitle, String kind) {
            this.uri = uri;
            this.title = title;
            this.subtitle = subtitle;
            this.kind = kind;
        }

        public boolean isPlayable() {
            return "track".equals(kind) || "episode".equals(kind);
        }

        public boolean isHeader() {
            return "header".equals(kind);
        }

        public static Item header(String title) {
            return new Item("", title, "", "header");
        }

        public static Item make(String uri, String title, String subtitle, String kind) {
            return new Item(uri, title, subtitle, kind);
        }
    }

    public interface Result {
        /** @param next ruta de la página siguiente o null si no hay más. */
        void onItems(List<Item> items, String next);

        void onError(String message);
    }

    /** Resultado de una escritura (Me gusta, cola). */
    public interface Done {
        void onDone(boolean ok, String message);
    }

    public interface Refs {
        void onRefs(String albumUri, String artistUri);
    }

    public interface Flags {
        void onFlags(boolean[] values);
    }

    private interface TokenCb {
        void onToken(String token, String error);
    }

    private interface Parser {
        /** @return la ruta de la página siguiente, o null. */
        String parse(JSONObject o, List<Item> out) throws Exception;
    }

    private static final String SCOPES =
            "playlist-read-private,playlist-read-collaborative,user-library-read,user-read-private";
    private static final String API = "https://api.spotify.com";
    private static WebApi instance;

    public static WebApi get(android.content.Context c) {
        if (instance == null) {
            instance = new WebApi(c.getApplicationContext());
            NativeEngine.addListener(instance);
        }
        return instance;
    }

    private final android.util.SparseArray<TokenCb> pending = new android.util.SparseArray<>();
    private final android.content.Context app;
    private int nextReq = 1;
    private String token;
    private long tokenExpiry;

    /** Mientras Spotify pide esperar (Retry-After) no se manda nada: reintentar empeora el límite. */
    private long cooldownUntil;

    /** Cuántas veces la app compartida recibió 429 (para sugerir la app propia). */
    public int sharedRateLimits;

    private WebApi(android.content.Context app) {
        this.app = app;
    }

    // ================================================================ lecturas

    public void search(String query, Result r) {
        read("/v1/search?type=track,album,playlist,artist&limit=8&q=" + enc(query), r, new Parser() {
            @Override
            public String parse(JSONObject o, List<Item> out) {
                section(o.optJSONObject("tracks"), "track", "Canciones", out);
                section(o.optJSONObject("artists"), "artist", "Artistas", out);
                section(o.optJSONObject("albums"), "album", "Álbumes", out);
                section(o.optJSONObject("playlists"), "playlist", "Playlists", out);
                return null;
            }
        });
    }

    public void likedTracks(Result r) {
        page("/v1/me/tracks?limit=50", r);
    }

    public void myPlaylists(Result r) {
        page("/v1/me/playlists?limit=50", r);
    }

    public void savedAlbums(Result r) {
        page("/v1/me/albums?limit=50", r);
    }

    public void followedArtists(Result r) {
        page("/v1/me/following?type=artist&limit=50", r);
    }

    public void recentlyPlayed(Result r) {
        read("/v1/me/player/recently-played?limit=50", r, new Parser() {
            @Override
            public String parse(JSONObject o, List<Item> out) {
                JSONArray items = o.optJSONArray("items");
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject h = items.optJSONObject(i);
                    Item it = h == null ? null : item(h.optJSONObject("track"), "track");
                    if (it != null && seen.add(it.uri)) { // sin repetidos seguidos
                        out.add(it);
                    }
                }
                return null;
            }
        });
    }

    public void playlistItems(String playlistUri, Result r) {
        page("/v1/playlists/" + id(playlistUri) + "/items?limit=100&additional_types=track,episode", r);
    }

    public void albumTracks(final String albumUri, Result r) {
        page("/v1/albums/" + id(albumUri) + "/tracks?limit=50", r);
    }

    /** Página de artista: populares + álbumes (dos pedidos, en secuencia). */
    public void artist(final String artistUri, final Result r) {
        final String id = id(artistUri);
        read("/v1/artists/" + id + "/top-tracks", new Result() {
            @Override
            public void onItems(final List<Item> top, String next) {
                read("/v1/artists/" + id + "/albums?include_groups=album,single&limit=50", new Result() {
                    @Override
                    public void onItems(List<Item> albums, String next) {
                        List<Item> all = new ArrayList<>(top);
                        if (!albums.isEmpty()) {
                            all.add(Item.header("Discografía"));
                            all.addAll(albums);
                        }
                        r.onItems(all, next);
                    }

                    @Override
                    public void onError(String message) {
                        r.onItems(top, null);
                    }
                }, PAGE);
            }

            @Override
            public void onError(String message) {
                r.onError(message);
            }
        }, new Parser() {
            @Override
            public String parse(JSONObject o, List<Item> out) {
                JSONArray tracks = o.optJSONArray("tracks");
                if (tracks != null && tracks.length() > 0) {
                    out.add(Item.header("Populares"));
                }
                for (int i = 0; tracks != null && i < tracks.length(); i++) {
                    Item it = item(tracks.optJSONObject(i), "track");
                    if (it != null) {
                        out.add(it);
                    }
                }
                return null;
            }
        });
    }

    /** Cola actual del dispositivo activo (este teléfono cuando está sonando). */
    public void queue(Result r) {
        read("/v1/me/player/queue", r, new Parser() {
            @Override
            public String parse(JSONObject o, List<Item> out) {
                Item now = item(o.optJSONObject("currently_playing"), "track");
                if (now != null) {
                    out.add(Item.header("Sonando"));
                    out.add(now);
                }
                JSONArray q = o.optJSONArray("queue");
                if (q != null && q.length() > 0) {
                    out.add(Item.header("A continuación"));
                }
                for (int i = 0; q != null && i < q.length(); i++) {
                    Item it = item(q.optJSONObject(i), "track");
                    if (it != null) {
                        out.add(it);
                    }
                }
                return null;
            }
        });
    }

    /** Página siguiente de cualquier lista ({@code next} de un {@link Result}). */
    public void page(String path, Result r) {
        read(path, r, PAGE);
    }

    /** Si cada URI está en la biblioteca (Me gusta / guardado). */
    public void contains(String uri, final Flags f) {
        read("/v1/me/library/contains?uris=" + enc(uri), new Result() {
            @Override
            public void onItems(List<Item> items, String next) {
            }

            @Override
            public void onError(String message) {
            }
        }, new Parser() {
            @Override
            public String parse(JSONObject o, List<Item> out) {
                return null;
            }
        }, f);
    }

    /** Álbum y artista de una pista (el motor no los trae): un pedido, solo a demanda. */
    public void trackRefs(String trackUri, final Refs refs) {
        if (!trackUri.startsWith("spotify:track:")) {
            return;
        }
        read("/v1/tracks/" + id(trackUri), new Result() {
            @Override
            public void onItems(List<Item> items, String next) {
                if (!items.isEmpty()) {
                    refs.onRefs(items.get(0).albumUri, items.get(0).artistUri);
                }
            }

            @Override
            public void onError(String message) {
            }
        }, new Parser() {
            @Override
            public String parse(JSONObject o, List<Item> out) {
                Item it = item(o, "track");
                if (it != null) {
                    out.add(it);
                }
                return null;
            }
        });
    }

    // ================================================================ escrituras

    public void save(String uri, boolean saved, Done d) {
        write(saved ? "PUT" : "DELETE", "/v1/me/library?uris=" + enc(uri), d);
    }

    public void addToQueue(String uri, Done d) {
        write("POST", "/v1/me/player/queue?uri=" + enc(uri), d);
    }

    // ================================================================ parseo

    /** Parser genérico de páginas: entiende items de playlists, guardados, álbumes y seguidos. */
    private static final Parser PAGE = new Parser() {
        @Override
        public String parse(JSONObject o, List<Item> out) {
            JSONObject paging = o.has("artists") ? o.optJSONObject("artists") : o; // /me/following
            JSONArray items = paging.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                JSONObject inner = it.optJSONObject("item"); // /playlists/{id}/items (2026)
                if (inner == null) {
                    inner = it.optJSONObject("track"); // /me/tracks y formato viejo
                }
                if (inner == null) {
                    inner = it.optJSONObject("album"); // /me/albums
                }
                Item parsed = item(inner != null ? inner : it, null);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
            String next = paging.optString("next", "");
            if (next.length() == 0 || "null".equals(next)) {
                return null;
            }
            return next.startsWith(API) ? next.substring(API.length()) : null;
        }
    };

    static Item item(JSONObject o, String fallbackKind) {
        if (o == null || o.optString("uri").length() == 0) {
            return null;
        }
        String kind = o.optString("type", fallbackKind == null ? "" : fallbackKind);
        String name = o.optString("name");
        String sub;
        Item it;
        switch (kind) {
            case "track": {
                JSONObject album = o.optJSONObject("album");
                String albumName = album == null ? "" : album.optString("name");
                sub = artists(o) + (albumName.length() > 0 ? " · " + albumName : "");
                it = new Item(o.optString("uri"), name, sub, kind);
                it.albumUri = album == null ? null : album.optString("uri", null);
                it.artistUri = firstArtist(o);
                return it;
            }
            case "episode": {
                JSONObject show = o.optJSONObject("show");
                sub = "Episodio" + (show == null ? "" : " · " + show.optString("name"));
                return new Item(o.optString("uri"), name, sub, kind);
            }
            case "album":
                sub = ("single".equals(o.optString("album_type")) ? "Sencillo" : "Álbum")
                        + " · " + artists(o) + year(o);
                it = new Item(o.optString("uri"), name, sub, kind);
                it.artistUri = firstArtist(o);
                return it;
            case "playlist": {
                JSONObject owner = o.optJSONObject("owner");
                sub = "Playlist" + (owner == null ? "" : " · " + owner.optString("display_name"));
                return new Item(o.optString("uri"), name, sub, kind);
            }
            case "artist":
                return new Item(o.optString("uri"), name, "Artista", kind);
            case "show":
                return new Item(o.optString("uri"), name, "Podcast", kind);
            default:
                return null;
        }
    }

    private static void section(JSONObject paging, String kind, String title, List<Item> out) {
        JSONArray items = paging == null ? null : paging.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return;
        }
        out.add(Item.header(title));
        for (int i = 0; i < items.length(); i++) {
            Item it = item(items.optJSONObject(i), kind); // la API a veces devuelve null
            if (it != null) {
                out.add(it);
            }
        }
    }

    private static String artists(JSONObject it) {
        JSONArray a = it.optJSONArray("artists");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject ar = a.optJSONObject(i);
            if (ar == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ar.optString("name"));
        }
        return sb.toString();
    }

    private static String firstArtist(JSONObject it) {
        JSONArray a = it.optJSONArray("artists");
        JSONObject first = a == null ? null : a.optJSONObject(0);
        return first == null ? null : first.optString("uri", null);
    }

    private static String year(JSONObject album) {
        String d = album.optString("release_date", "");
        return d.length() >= 4 ? " · " + d.substring(0, 4) : "";
    }

    /** spotify:album:ABC → ABC */
    public static String id(String uri) {
        int i = uri == null ? -1 : uri.lastIndexOf(':');
        return i < 0 ? String.valueOf(uri) : uri.substring(i + 1);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ================================================================ transporte

    private void read(String path, Result r, Parser parser) {
        read(path, r, parser, null);
    }

    private void read(final String path, final Result r, final Parser parser, final Flags flags) {
        request("GET", path, new Response() {
            @Override
            public void ok(byte[] body) {
                try {
                    String text = new String(body, "UTF-8");
                    if (flags != null) {
                        JSONArray a = new JSONArray(text);
                        boolean[] v = new boolean[a.length()];
                        for (int i = 0; i < v.length; i++) {
                            v[i] = a.optBoolean(i);
                        }
                        flags.onFlags(v);
                        return;
                    }
                    List<Item> out = new ArrayList<>();
                    String next = parser.parse(new JSONObject(text), out);
                    r.onItems(out, next);
                } catch (Exception e) {
                    r.onError("respuesta inválida");
                }
            }

            @Override
            public void fail(String message) {
                r.onError(message);
            }
        });
    }

    private void write(String method, String path, final Done d) {
        request(method, path, new Response() {
            @Override
            public void ok(byte[] body) {
                d.onDone(true, null);
            }

            @Override
            public void fail(String message) {
                d.onDone(false, message);
            }
        });
    }

    private interface Response {
        void ok(byte[] body);

        void fail(String message);
    }

    /** Fuente del token, como en Spotifast: la app propia tiene cupo propio de pedidos. */
    private interface SourcedToken {
        void onToken(String token, boolean personal, String error);
    }

    private void request(final String method, final String path, final Response res) {
        long wait = cooldownUntil - SystemClock.elapsedRealtime();
        if (wait > 0) {
            res.fail(rateLimitMessage(!OAuthTokens.has(app, OAuthTokens.PERSONAL), wait / 1000 + 1));
            return;
        }
        withToken(new SourcedToken() {
            @Override
            public void onToken(final String tok, final boolean personal, String error) {
                if (tok == null) {
                    // Mensaje corto: el detalle técnico va al log, no a la pantalla.
                    android.util.Log.w("spotifast", "token Web API: " + error);
                    res.fail("no hay permiso para la Web API. Configurá tu app de Spotify en Ajustes.");
                    return;
                }
                JSONObject headers = new JSONObject();
                try {
                    headers.put("Authorization", "Bearer " + tok);
                } catch (Exception ignored) {
                }
                // HTTPS por el motor: mismo comportamiento en Android 4.4 y en 15.
                NativeEngine.http(method, API + path, headers, null, new NativeEngine.HttpCallback() {
                    @Override
                    public void onResponse(int status, byte[] body, String retryAfter) {
                        if (status == 429) {
                            long secs = parseSeconds(retryAfter);
                            cooldownUntil = SystemClock.elapsedRealtime() + secs * 1000;
                            if (!personal) {
                                sharedRateLimits++;
                            }
                            res.fail(rateLimitMessage(!personal, secs));
                            return;
                        }
                        if (status == 401) {
                            if (personal) {
                                OAuthTokens.invalidate(app, OAuthTokens.PERSONAL);
                            } else {
                                OAuthTokens.invalidate(app, OAuthTokens.SHARED);
                                token = null;
                            }
                        }
                        if (status < 200 || status >= 300) {
                            android.util.Log.w("spotifast", method + " " + path + " → " + status + ": "
                                    + new String(body));
                            res.fail(status == 0 ? "sin conexión"
                                    : status == 403 ? "Spotify no dio permiso. Volvé a tocar Autorizar en Ajustes."
                                    : status == 404 ? "no hay nada sonando en este teléfono"
                                    : "Web API " + status);
                            return;
                        }
                        res.ok(body);
                    }
                });
            }
        });
    }

    private static long parseSeconds(String retryAfter) {
        try {
            return Math.max(1, Math.min(600, Long.parseLong(retryAfter.trim())));
        } catch (Exception e) {
            return 30; // sin Retry-After: pausa prudente
        }
    }

    private static String rateLimitMessage(boolean shared, long secs) {
        return shared
                ? "Spotify limitó la app compartida (reintentá en " + secs + " s). Para que no pase, "
                  + "configurá tu propia app de Spotify en Ajustes, como en Spotifast."
                : "Spotify pidió esperar " + secs + " s antes de seguir.";
    }

    /** 1) app propia del usuario, 2) token del login de reproducción, 3) token login5 del motor. */
    private void withToken(final SourcedToken cb) {
        OAuthTokens.get(app, OAuthTokens.PERSONAL, new OAuthTokens.Cb() {
            @Override
            public void onToken(String personal) {
                if (personal != null) {
                    cb.onToken(personal, true, null);
                    return;
                }
                OAuthTokens.get(app, OAuthTokens.SHARED, new OAuthTokens.Cb() {
                    @Override
                    public void onToken(String shared) {
                        if (shared != null) {
                            cb.onToken(shared, false, null);
                            return;
                        }
                        if (token != null && SystemClock.elapsedRealtime() < tokenExpiry) {
                            cb.onToken(token, false, null);
                            return;
                        }
                        int id = nextReq++;
                        pending.put(id, new TokenCb() {
                            @Override
                            public void onToken(String t, String error) {
                                cb.onToken(t, false, error);
                            }
                        });
                        NativeEngine.requestToken(SCOPES, id);
                    }
                });
            }
        });
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
}
