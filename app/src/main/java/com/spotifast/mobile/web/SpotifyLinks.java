package com.spotifast.mobile.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Convierte enlaces de open.spotify.com (o texto compartido que los contenga) en URIs spotify:. */
public final class SpotifyLinks {

    private static final Pattern WEB = Pattern.compile(
            "open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(track|album|playlist|episode|show|artist)/([A-Za-z0-9]{22})");
    private static final Pattern URI = Pattern.compile(
            "spotify:(track|album|playlist|episode|show|artist):([A-Za-z0-9]{22})");

    private SpotifyLinks() {
    }

    /** @return el URI spotify:tipo:id o null si el texto no contiene un enlace reconocible. */
    public static String toUri(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = WEB.matcher(text);
        if (m.find()) {
            return "spotify:" + m.group(1) + ":" + m.group(2);
        }
        m = URI.matcher(text);
        if (m.find()) {
            return "spotify:" + m.group(1) + ":" + m.group(2);
        }
        return null;
    }
}
