package app.tinyl.lyrics;

import android.content.Context;
import android.util.SparseArray;

import app.tinyl.engine.NativeEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Letras de la canción que suena, como en Spotifast: primero las de Spotify (sincronizadas por
 * línea), después LRCLIB (lrclib.net, libre y sin cuenta). Las respuestas se guardan en disco
 * 30 días ("no hay" solo 7), así una canción repetida no vuelve a pedir nada.
 */
public final class Lyrics {

    public static final class Line {
        public final long atMs; // -1 si no tiene tiempo
        public final String text;

        public Line(long atMs, String text) {
            this.atMs = atMs;
            this.text = text;
        }
    }

    public interface Cb {
        /** @param lyrics null si no hay letra. */
        void onLyrics(String trackUri, Lyrics lyrics, String source);
    }

    public final List<Line> lines = new ArrayList<>();
    public boolean synced;
    public String source = "";

    /** Índice de la línea que se canta en {@code posMs}, o -1. */
    public int activeLine(long posMs) {
        if (!synced) {
            return -1;
        }
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).atMs <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    // ================================================================ carga

    private static final long FOUND_TTL = 30L * 24 * 3600 * 1000;
    private static final long NONE_TTL = 7L * 24 * 3600 * 1000;
    private static final SparseArray<Cb> PENDING = new SparseArray<>();
    private static final SparseArray<Object[]> PENDING_ARGS = new SparseArray<>();
    private static int nextReq = 1;
    private static boolean listening;

    /**
     * @param coverUrl URL de la carátula (el ID de imagen va en el pedido a Spotify).
     */
    public static void load(final Context c, final String trackUri, String coverUrl, final String title,
                            final String artist, final String album, final long durationMs, final Cb cb) {
        if (trackUri == null || !trackUri.startsWith("spotify:track:")) {
            cb.onLyrics(trackUri, null, "");
            return;
        }
        final File cache = cacheFile(c, trackUri);
        Lyrics cached = readCache(cache);
        if (cached != null) {
            cb.onLyrics(trackUri, cached.lines.isEmpty() ? null : cached, cached.source);
            return;
        }
        listen();
        int id = nextReq++;
        PENDING.put(id, cb);
        PENDING_ARGS.put(id, new Object[]{c.getApplicationContext(), trackUri, title, artist, album, durationMs});
        String coverId = null;
        if (coverUrl != null) {
            int slash = coverUrl.lastIndexOf('/');
            coverId = slash >= 0 ? coverUrl.substring(slash + 1) : null;
        }
        NativeEngine.lyrics(trackUri, coverId, id);
    }

    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        NativeEngine.addListener(new NativeEngine.Listener() {
            @Override
            public void onEngineEvent(String type, JSONObject ev) {
                if (!"lyrics".equals(type)) {
                    return;
                }
                int id = ev.optInt("req_id");
                Cb cb = PENDING.get(id);
                Object[] a = PENDING_ARGS.get(id);
                PENDING.remove(id);
                PENDING_ARGS.remove(id);
                if (cb == null || a == null) {
                    return;
                }
                Context c = (Context) a[0];
                String uri = (String) a[1];
                Lyrics l = "found".equals(ev.optString("status")) ? fromSpotify(ev.optString("json")) : null;
                if (l != null) {
                    l.source = "Spotify";
                    writeCache(cacheFile(c, uri), l);
                    cb.onLyrics(uri, l, l.source);
                } else {
                    lrclib(c, uri, (String) a[2], (String) a[3], (String) a[4], (Long) a[5], cb);
                }
            }
        });
    }

    /** LRCLIB: búsqueda exacta y, si no, la versión cuya duración más se parece. */
    private static void lrclib(final Context c, final String uri, String title, String artist, String album,
                               final long durationMs, final Cb cb) {
        String firstArtist = artist == null ? "" : artist.split(",")[0].trim();
        final String q = "track_name=" + enc(title) + "&artist_name=" + enc(firstArtist);
        String get = "https://lrclib.net/api/get?" + q + "&album_name=" + enc(album)
                + "&duration=" + Math.round(durationMs / 1000.0);
        final JSONObject headers = new JSONObject();
        try {
            headers.put("User-Agent", "Tinyl/0.6 (Android)");
        } catch (Exception ignored) {
        }
        NativeEngine.http("GET", get, headers, null, new NativeEngine.HttpCallback() {
            @Override
            public void onResponse(int status, byte[] body, String retryAfter) {
                Lyrics l = status == 200 ? fromLrclib(text(body)) : null;
                if (l != null) {
                    done(c, uri, l, cb);
                    return;
                }
                NativeEngine.http("GET", "https://lrclib.net/api/search?" + q, headers, null,
                        new NativeEngine.HttpCallback() {
                            @Override
                            public void onResponse(int status, byte[] body, String retryAfter) {
                                Lyrics best = null;
                                if (status == 200) {
                                    try {
                                        JSONArray a = new JSONArray(text(body));
                                        double bestDiff = 31; // otra grabación si difiere más de 30 s
                                        for (int i = 0; i < a.length(); i++) {
                                            JSONObject o = a.optJSONObject(i);
                                            if (o == null) {
                                                continue;
                                            }
                                            double diff = Math.abs(o.optDouble("duration", 0) - durationMs / 1000.0);
                                            Lyrics cand = fromLrclib(o.toString());
                                            if (cand != null && (diff < bestDiff
                                                    || (diff == bestDiff && cand.synced && best != null && !best.synced))) {
                                                best = cand;
                                                bestDiff = diff;
                                            }
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                                if (best == null && status != 0) {
                                    writeCache(cacheFile(c, uri), new Lyrics()); // "no hay", por 7 días
                                }
                                done(c, uri, best, cb);
                            }
                        });
            }
        });
    }

    private static void done(Context c, String uri, Lyrics l, Cb cb) {
        if (l != null) {
            l.source = "LRCLIB";
            writeCache(cacheFile(c, uri), l);
        }
        cb.onLyrics(uri, l, l == null ? "" : l.source);
    }

    // ================================================================ parseo

    static Lyrics fromSpotify(String json) {
        try {
            JSONObject lyr = new JSONObject(json).optJSONObject("lyrics");
            if (lyr == null) {
                return null;
            }
            Lyrics l = new Lyrics();
            l.synced = "LINE_SYNCED".equals(lyr.optString("syncType"));
            JSONArray lines = lyr.optJSONArray("lines");
            for (int i = 0; lines != null && i < lines.length(); i++) {
                JSONObject o = lines.optJSONObject(i);
                String t = o == null ? "" : o.optString("words").trim();
                if (t.length() == 0 || "♪".equals(t)) {
                    continue;
                }
                long at = l.synced ? o.optLong("startTimeMs", -1) : -1;
                l.lines.add(new Line(at, t));
            }
            for (Line line : l.lines) {
                if (line.atMs < 0) {
                    l.synced = false;
                }
            }
            return l.lines.isEmpty() ? null : l;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern LRC = Pattern.compile("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)$");

    static Lyrics fromLrclib(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.optBoolean("instrumental")) {
                return null;
            }
            String synced = o.optString("syncedLyrics", "");
            Lyrics l = new Lyrics();
            if (synced.length() > 0 && !"null".equals(synced)) {
                l.synced = true;
                for (String raw : synced.split("\n")) {
                    Matcher m = LRC.matcher(raw.trim());
                    if (!m.matches()) {
                        continue;
                    }
                    String frac = m.group(3) == null ? "0" : m.group(3);
                    long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000
                            + (long) (Double.parseDouble("0." + frac) * 1000);
                    String t = m.group(4).trim();
                    if (t.length() > 0) {
                        l.lines.add(new Line(ms, t));
                    }
                }
            }
            if (l.lines.isEmpty()) {
                l.synced = false;
                String plain = o.optString("plainLyrics", "");
                if (plain.length() == 0 || "null".equals(plain)) {
                    return null;
                }
                for (String t : plain.split("\n")) {
                    if (t.trim().length() > 0) {
                        l.lines.add(new Line(-1, t.trim()));
                    }
                }
            }
            return l.lines.isEmpty() ? null : l;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ caché en disco

    private static File cacheFile(Context c, String uri) {
        File dir = new File(c.getFilesDir(), "lyrics");
        dir.mkdirs();
        return new File(dir, uri.substring(uri.lastIndexOf(':') + 1) + ".json");
    }

    private static Lyrics readCache(File f) {
        if (!f.exists()) {
            return null;
        }
        try {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            in.close();
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            Lyrics l = new Lyrics();
            l.synced = o.optBoolean("synced");
            l.source = o.optString("source");
            JSONArray a = o.optJSONArray("lines");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject line = a.getJSONObject(i);
                l.lines.add(new Line(line.optLong("t", -1), line.optString("x")));
            }
            long age = System.currentTimeMillis() - f.lastModified();
            if (age > (l.lines.isEmpty() ? NONE_TTL : FOUND_TTL)) {
                return null;
            }
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(File f, Lyrics l) {
        try {
            JSONObject o = new JSONObject();
            o.put("synced", l.synced);
            o.put("source", l.source);
            JSONArray a = new JSONArray();
            for (Line line : l.lines) {
                a.put(new JSONObject().put("t", line.atMs).put("x", line.text));
            }
            o.put("lines", a);
            FileOutputStream out = new FileOutputStream(f);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static String text(byte[] b) {
        try {
            return b == null ? "" : new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
