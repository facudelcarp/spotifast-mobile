package app.tinyl.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import app.tinyl.PlayerService;
import app.tinyl.Prefs;
import app.tinyl.R;
import app.tinyl.SpotifyAuth;
import app.tinyl.web.OAuthTokens;

import java.io.File;

/** Ajustes armados en código: sin XML extra ni librerías de preferencias. */
public final class SettingsActivity extends Activity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        final EditText name = field(root, R.string.device_name, prefs.deviceName(), InputType.TYPE_CLASS_TEXT);

        label(root, R.string.quality);
        final RadioGroup quality = new RadioGroup(this);
        int[] rates = {96, 160, 320};
        String[] labels = {"96 kbps · ahorro", "160 kbps · normal", "320 kbps · alta"};
        for (int i = 0; i < rates.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(rates[i]);
            rb.setText(labels[i]);
            quality.addView(rb);
        }
        quality.check(prefs.bitrate());
        root.addView(quality);

        final CheckBox eco = new CheckBox(this);
        eco.setText(R.string.eco);
        eco.setChecked(prefs.eco());
        root.addView(eco);

        final CheckBox autoplay = new CheckBox(this);
        autoplay.setText(R.string.autoplay);
        autoplay.setChecked(prefs.autoplay());
        root.addView(autoplay);

        final EditText cache = field(root, R.string.cache, String.valueOf(prefs.cacheMb()), InputType.TYPE_CLASS_NUMBER);
        final TextView cacheUse = new TextView(this);
        cacheUse.setTextSize(12);
        root.addView(cacheUse);
        showCacheUse(cacheUse);
        final CheckBox wifiOnly = new CheckBox(this);
        wifiOnly.setText(R.string.prefetch_wifi_only);
        wifiOnly.setChecked(prefs.prefetchWifiOnly());
        root.addView(wifiOnly);
        Button clear = new Button(this);
        clear.setText(R.string.cache_clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Apagar el motor antes de borrar archivos que podría estar usando.
                stopService(new Intent(SettingsActivity.this, PlayerService.class));
                deleteTree(new File(getCacheDir(), "audio"));
                showCacheUse(cacheUse);
            }
        });
        root.addView(clear);
        final EditText idle = field(root, R.string.idle, String.valueOf(prefs.idleMinutes()), InputType.TYPE_CLASS_NUMBER);

        // ---- Web API con app propia: cupo propio, sin 429 de la app compartida
        label(root, R.string.web_api);
        TextView help = new TextView(this);
        help.setTextSize(12);
        help.setText(getString(R.string.web_api_help, SpotifyAuth.PERSONAL_REDIRECT));
        root.addView(help);
        Button dashboard = new Button(this);
        dashboard.setText(R.string.web_api_dashboard);
        dashboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://developer.spotify.com/dashboard")));
            }
        });
        root.addView(dashboard);
        final EditText clientId = field(root, R.string.web_api_client_id, prefs.webClientId(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final TextView webStatus = new TextView(this);
        webStatus.setTextSize(12);
        webStatus.setText(OAuthTokens.has(this, OAuthTokens.PERSONAL)
                ? R.string.web_api_connected : R.string.web_api_not_connected);
        root.addView(webStatus);
        Button authorize = new Button(this);
        authorize.setText(R.string.web_api_authorize);
        authorize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String id = clientId.getText().toString().trim();
                if (!SpotifyAuth.looksLikeClientId(id)) {
                    webStatus.setText(R.string.web_api_bad_id);
                    return;
                }
                prefs.setWebClientId(id);
                webStatus.setText(R.string.web_api_waiting);
                SpotifyAuth.login(SettingsActivity.this, SpotifyAuth.Grant.personal(id),
                        new SpotifyAuth.Callback() {
                            @Override
                            public void onToken(String accessToken) {
                                webStatus.setText(R.string.web_api_connected);
                            }

                            @Override
                            public void onError(String message) {
                                webStatus.setText(message);
                            }
                        });
            }
        });
        root.addView(authorize);

        Button save = new Button(this);
        save.setText(R.string.save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setDeviceName(name.getText().toString().trim().isEmpty()
                        ? prefs.deviceName() : name.getText().toString().trim());
                prefs.setBitrate(quality.getCheckedRadioButtonId());
                prefs.setEco(eco.isChecked());
                prefs.setAutoplay(autoplay.isChecked());
                prefs.setCacheMb(parse(cache, 512));
                prefs.setPrefetchWifiOnly(wifiOnly.isChecked());
                prefs.setIdleMinutes(Math.max(1, parse(idle, 10)));
                // Nombre, calidad y caché se aplican al reiniciar el motor.
                stopService(new Intent(SettingsActivity.this, PlayerService.class));
                finish();
            }
        });
        root.addView(save);

        Button logout = new Button(this);
        logout.setText(R.string.logout);
        logout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(SettingsActivity.this, PlayerService.class));
                new File(getFilesDir(), "credentials.json").delete();
                app.tinyl.web.OAuthTokens.clearAll(SettingsActivity.this);
                finish();
            }
        });
        root.addView(logout);

        TextView about = new TextView(this);
        about.setPadding(0, pad, 0, 0);
        about.setTextSize(11);
        about.setText("Tinyl · cliente liviano para Spotify. Requiere Spotify Premium.\n"
                + "Motor: librespot (código abierto). Protocolo de control compatible con Spotify Soloist. "
                + "Inspirado en Spotifast. No afiliado a Spotify AB.");
        root.addView(about);

        ScrollView sv = new ScrollView(this);
        // Android 15 dibuja de borde a borde: que el contenido no quede bajo las barras del sistema.
        sv.setFitsSystemWindows(true);
        sv.addView(root);
        setContentView(sv);
    }

    /** Cuánto ocupa la caché de audio (canciones escuchadas y precargadas). */
    private void showCacheUse(TextView t) {
        long bytes = size(new File(getCacheDir(), "audio"));
        t.setText(getString(R.string.cache_use, bytes / (1024 * 1024)));
    }

    private static long size(File f) {
        if (f.isFile()) {
            return f.length();
        }
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                total += size(c);
            }
        }
        return total;
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }

    private EditText field(LinearLayout root, int labelRes, String value, int type) {
        label(root, labelRes);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setText(value);
        root.addView(e);
        return e;
    }

    private void label(LinearLayout root, int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setPadding(0, dp(12), 0, 0);
        t.setTextColor(getResources().getColor(R.color.accent));
        root.addView(t);
    }

    private static int parse(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
