package com.spotifast.mobile.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;

import com.spotifast.mobile.Prefs;
import com.spotifast.mobile.engine.EngineState;
import com.spotifast.mobile.web.WebApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Modo demo para revisar el diseño sin cuenta de Spotify (como `--demo` de Spotifast).
 * Solo en builds depurables:
 * {@code adb shell am start -n com.spotifast.mobile/.ui.MainActivity --es demo home|search|library|playlist|playing}
 */
final class Demo {

    private Demo() {
    }

    static boolean requested(MainActivity a, Intent i) {
        boolean debuggable = (a.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return debuggable && i != null && i.hasExtra("demo");
    }

    private static WebApi.Item it(String uri, String title, String sub, String kind) {
        return WebApi.Item.make(uri, title, sub, kind);
    }

    static void fill(MainActivity a, Browser browser) {
        String mode = a.getIntent().getStringExtra("demo");
        EngineState s = EngineState.get();
        try {
            s.onEngineEvent("auth_state", new JSONObject(
                    "{\"logged_in\":true,\"is_active\":true,\"device_name\":\"Spotifast demo\"}"));
            s.onEngineEvent("session_info", new JSONObject("{\"username\":\"demo\"}"));
            s.onEngineEvent("track_changed", new JSONObject("{\"item\":{\"uri\":\"spotify:track:demo1\","
                    + "\"entity_type\":\"track\",\"decorations\":{\"identity\":{\"name\":\"A Toda Hora\"},"
                    + "\"parent\":{\"entity\":{\"decorations\":{\"identity\":{\"name\":\"Vol. 3\"}}}},"
                    + "\"creators\":[{\"entity\":{\"decorations\":{\"identity\":{\"name\":\"Los Demo\"}}}}],"
                    + "\"playback\":{\"duration_ms\":214000}}}}"));
            s.onEngineEvent("playback_changed", new JSONObject("{\"status\":\"playing\"}"));
            s.onEngineEvent("position_sync", new JSONObject("{\"position\":{\"position_ms\":83000,"
                    + "\"timestamp_ms\":" + System.currentTimeMillis() + ",\"speed\":1.0}}"));
            s.onEngineEvent("volume_changed", new JSONObject("{\"volume\":70}"));
        } catch (Exception ignored) {
        }
        List<WebApi.Item> tracks = new ArrayList<>();
        String[][] t = {
                {"A Toda Hora", "Los Demo · Vol. 3"}, {"Ruta 40", "Banda Ejemplo · Kilómetro Cero"},
                {"Noche de Verano", "La Prueba · Singles"}, {"Mar del Plata", "Los Demo · Vol. 2"},
                {"Luces de Neón", "Synth Sur · Nocturno"}, {"Domingo", "Trío Muestra · Domingo"}};
        for (int i = 0; i < t.length; i++) {
            tracks.add(it(i == 0 ? "spotify:track:demo1" : "spotify:track:x" + i, t[i][0], t[i][1], "track"));
        }
        if ("search".equals(mode)) {
            Prefs p = new Prefs(a);
            p.clearSearches();
            for (String q : new String[]{"tame impala", "cero", "ysy a", "lil peep"}) {
                p.addSearch(q);
            }
            a.demoTab(MainActivity.DEMO_SEARCH);
            browser.showSearchRoot();
        } else if ("library".equals(mode) || "playlist".equals(mode)) {
            a.demoTab(MainActivity.DEMO_LIBRARY);
            List<WebApi.Item> lib = new ArrayList<>();
            lib.add(it("liked", "Tus me gusta", "Playlist · canciones guardadas", "liked"));
            lib.add(it("spotify:playlist:a", "Para el viaje", "Playlist · facu", "playlist"));
            lib.add(it("spotify:album:b", "EL TRAP DE TU PAÍS", "Álbum · YSY A", "album"));
            lib.add(it("spotify:playlist:c", "milanesas y compu", "Playlist · santti!", "playlist"));
            lib.add(it("spotify:album:d", "LIVE FOREVER", "Álbum · Lil Peep", "album"));
            browser.showStatic("Tu biblioteca", null, lib, true);
            if ("playlist".equals(mode)) {
                browser.showStatic("Para el viaje", "spotify:playlist:a", tracks, false);
            }
        } else {
            a.demoTab(MainActivity.DEMO_HOME);
            List<WebApi.Item> home = new ArrayList<>();
            home.add(it("liked", "Tus me gusta", "", "liked"));
            home.add(it("spotify:playlist:a", "Para el viaje", "", "playlist"));
            home.add(it("spotify:album:b", "EL TRAP DE TU PAÍS", "", "album"));
            home.add(it("spotify:playlist:c", "milanesas y compu y marvelboy", "", "playlist"));
            home.add(it("spotify:artist:e", "El Madrileño", "", "artist"));
            home.add(it("spotify:album:d", "LIVE FOREVER", "", "album"));
            home.add(it("spotify:playlist:f", "TRAP DE VERDAD", "", "playlist"));
            home.add(it("spotify:playlist:g", "Mecánico", "", "playlist"));
            home.add(WebApi.Item.header("Escuchado recientemente"));
            home.addAll(tracks);
            browser.showStatic(Browser.greeting(), null, home, true, true);
        }
        if ("playing".equals(mode)) {
            a.findViewById(com.spotifast.mobile.R.id.mini).performClick();
        }
    }
}
