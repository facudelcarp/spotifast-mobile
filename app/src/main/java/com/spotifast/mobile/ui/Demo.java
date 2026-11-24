package com.spotifast.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import com.spotifast.mobile.engine.EngineState;
import com.spotifast.mobile.web.WebApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Modo demo para revisar el diseño sin cuenta de Spotify (como `--demo` de Spotifast).
 * Solo en builds depurables: {@code adb shell am start -n com.spotifast.mobile/.ui.MainActivity
 * --es demo library|playlist|playing}.
 */
final class Demo {

    private Demo() {
    }

    static boolean requested(Activity a, Intent i) {
        boolean debuggable = (a.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return debuggable && i != null && i.hasExtra("demo");
    }

    static void fill(Activity a, Browser browser) {
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
        if ("playlist".equals(mode)) {
            browser.showLibrary();
            List<WebApi.Item> items = new ArrayList<>();
            String[][] t = {
                    {"A Toda Hora", "Los Demo · Vol. 3"}, {"Ruta 40", "Banda Ejemplo · Kilómetro Cero"},
                    {"Noche de Verano", "La Prueba · Singles"}, {"Mar del Plata", "Los Demo · Vol. 2"},
                    {"Luces de Neón", "Synth Sur · Nocturno"}, {"Domingo", "Trío Muestra · Domingo"},
                    {"Todo Cambia", "Coro Demo · En Vivo"}};
            for (int i = 0; i < t.length; i++) {
                items.add(WebApi.Item.make(i == 0 ? "spotify:track:demo1" : "spotify:track:x" + i,
                        t[i][0], t[i][1], "track"));
            }
            browser.showStatic("Para el viaje", "spotify:playlist:demo", items, false);
        } else {
            browser.showLibrary();
        }
        if ("playing".equals(mode)) {
            a.findViewById(com.spotifast.mobile.R.id.mini).performClick();
        }
    }
}
