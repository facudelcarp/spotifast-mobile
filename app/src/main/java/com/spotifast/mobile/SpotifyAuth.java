package com.spotifast.mobile;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Login OAuth (PKCE) con el client ID de librespot, todo dentro del teléfono:
 * abre el navegador y atiende el redirect http://127.0.0.1:8898/login con un mini servidor.
 * El token obtenido se le pasa al motor, que guarda credenciales reutilizables y no vuelve
 * a pedir login.
 */
public final class SpotifyAuth {

    public interface Callback {
        void onToken(String accessToken);

        void onError(String message);
    }

    public static final String CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";
    private static final int PORT = 8898;
    private static final String REDIRECT = "http://127.0.0.1:" + PORT + "/login";
    private static final String SCOPES = "streaming user-read-private user-read-email "
            + "user-read-playback-state user-modify-playback-state user-read-currently-playing "
            + "playlist-read-private playlist-read-collaborative user-library-read";

    private static volatile ServerSocket server;

    private SpotifyAuth() {
    }

    public static void login(final Context ctx, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final String verifier = random(48);
        final String state = random(12);
        final String challenge;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            challenge = b64(md.digest(verifier.getBytes("US-ASCII")));
        } catch (Exception e) {
            cb.onError(e.toString());
            return;
        }
        closeServer();
        try {
            server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(5 * 60 * 1000);
        } catch (Exception e) {
            cb.onError("No se pudo abrir el puerto " + PORT + ": " + e.getMessage());
            return;
        }
        String url = "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&redirect_uri=" + enc(REDIRECT)
                + "&scope=" + enc(SCOPES)
                + "&code_challenge_method=S256&code_challenge=" + challenge
                + "&state=" + state;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String code = awaitCode(state);
                    final String token = exchange(code, verifier);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onToken(token);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(e.getMessage() == null ? e.toString() : e.getMessage());
                        }
                    });
                } finally {
                    closeServer();
                }
            }
        }, "spotifast-oauth").start();

        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private static String awaitCode(String expectedState) throws Exception {
        while (true) {
            Socket s = server.accept();
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line = r.readLine();
                String path = line == null ? "" : line.split(" ").length > 1 ? line.split(" ")[1] : "";
                Uri u = Uri.parse("http://127.0.0.1" + path);
                if (!"/login".equals(u.getPath())) {
                    respond(s, 404, "");
                    continue; // p. ej. /favicon.ico
                }
                String code = u.getQueryParameter("code");
                String err = u.getQueryParameter("error");
                boolean ok = code != null && expectedState.equals(u.getQueryParameter("state"));
                respond(s, 200, ok
                        ? "<h2>Listo</h2><p>Ya podés volver a Spotifast.</p>"
                          + "<p><a href=\"spotifast://login\">Abrir Spotifast</a></p>"
                        : "<h2>Error</h2><p>" + (err == null ? "respuesta inválida" : err) + "</p>");
                if (!ok) {
                    throw new Exception("Login cancelado o inválido" + (err == null ? "" : ": " + err));
                }
                return code;
            } finally {
                s.close();
            }
        }
    }

    private static void respond(Socket s, int status, String body) throws Exception {
        String html = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">"
                + "<body style=\"background:#000;color:#1ed760;font-family:monospace;padding:24px\">"
                + body + "</body>";
        byte[] b = html.getBytes("UTF-8");
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
        o.write(b);
        o.flush();
    }

    private static String exchange(String code, String verifier) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String body = "grant_type=authorization_code&code=" + enc(code)
                + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + CLIENT_ID
                + "&code_verifier=" + enc(verifier);
        OutputStream o = c.getOutputStream();
        o.write(body.getBytes("UTF-8"));
        o.close();
        int status = c.getResponseCode();
        String text = read(status < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (status >= 400) {
            throw new Exception("Spotify rechazó el login (" + status + "): " + text);
        }
        return new JSONObject(text).getString("access_token");
    }

    static String read(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        return sb.toString();
    }

    private static void closeServer() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return b64(b);
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
