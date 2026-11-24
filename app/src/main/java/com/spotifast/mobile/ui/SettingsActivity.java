package com.spotifast.mobile.ui;

import android.app.Activity;
import android.content.Intent;
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

import com.spotifast.mobile.PlayerService;
import com.spotifast.mobile.Prefs;
import com.spotifast.mobile.R;

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

        final EditText cache = field(root, R.string.cache, String.valueOf(prefs.cacheMb()), InputType.TYPE_CLASS_NUMBER);
        final EditText idle = field(root, R.string.idle, String.valueOf(prefs.idleMinutes()), InputType.TYPE_CLASS_NUMBER);

        Button save = new Button(this);
        save.setText(R.string.save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setDeviceName(name.getText().toString().trim().isEmpty()
                        ? prefs.deviceName() : name.getText().toString().trim());
                prefs.setBitrate(quality.getCheckedRadioButtonId());
                prefs.setEco(eco.isChecked());
                prefs.setCacheMb(parse(cache, 200));
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
                com.spotifast.mobile.web.OAuthTokens.clear(SettingsActivity.this);
                finish();
            }
        });
        root.addView(logout);

        TextView about = new TextView(this);
        about.setPadding(0, pad, 0, 0);
        about.setTextSize(11);
        about.setText("Motor: librespot (código abierto) dentro de la app. Protocolo de control "
                + "compatible con Spotify Soloist. No afiliado a Spotify.");
        root.addView(about);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
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
