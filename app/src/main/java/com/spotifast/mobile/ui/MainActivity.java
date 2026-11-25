package com.spotifast.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.spotifast.mobile.PlayerService;
import com.spotifast.mobile.Prefs;
import com.spotifast.mobile.R;
import com.spotifast.mobile.SpotifyAuth;
import com.spotifast.mobile.engine.EngineState;
import com.spotifast.mobile.engine.NativeEngine;
import com.spotifast.mobile.web.SpotifyLinks;
import com.spotifast.mobile.web.WebApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pantalla única. No hace polling: se redibuja con los eventos del motor y, solo mientras
 * está visible y sonando, actualiza el reloj una vez por segundo.
 */
public final class MainActivity extends Activity implements EngineState.Observer {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView status, time, duration, badge, title, subtitle, empty, loginError;
    private ImageView cover;
    private ImageButton play, shuffle, repeat;
    private SeekBar seek, volume;
    private View loginPanel;
    private android.widget.EditText query;
    private final Adapter adapter = new Adapter();
    private boolean userSeeking;
    private String pendingUri;
    private String shownCover;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock(EngineState.get());
            if (EngineState.get().isPlaying()) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        status = (TextView) findViewById(R.id.status);
        time = (TextView) findViewById(R.id.time);
        duration = (TextView) findViewById(R.id.duration);
        badge = (TextView) findViewById(R.id.badge);
        title = (TextView) findViewById(R.id.title);
        subtitle = (TextView) findViewById(R.id.subtitle);
        empty = (TextView) findViewById(R.id.empty);
        cover = (ImageView) findViewById(R.id.cover);
        play = (ImageButton) findViewById(R.id.btn_play);
        shuffle = (ImageButton) findViewById(R.id.btn_shuffle);
        repeat = (ImageButton) findViewById(R.id.btn_repeat);
        seek = (SeekBar) findViewById(R.id.seek);
        volume = (SeekBar) findViewById(R.id.volume);
        loginPanel = findViewById(R.id.login_panel);
        loginError = (TextView) findViewById(R.id.login_error);
        query = (android.widget.EditText) findViewById(R.id.query);

        ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                playUri(adapter.items.get(pos).uri);
            }
        });

        click(R.id.btn_play, new Runnable() {
            @Override
            public void run() {
                NativeEngine.command(EngineState.get().isPlaying() ? "pause" : "play");
            }
        });
        click(R.id.btn_next, cmd("skip_next"));
        click(R.id.btn_prev, cmd("skip_prev"));
        click(R.id.btn_shuffle, new Runnable() {
            @Override
            public void run() {
                NativeEngine.command("set_shuffle", "enabled", !EngineState.get().shuffle);
            }
        });
        click(R.id.btn_repeat, new Runnable() {
            @Override
            public void run() {
                cycleRepeat(EngineState.get().repeat);
            }
        });
        click(R.id.btn_search, new Runnable() {
            @Override
            public void run() {
                submitQuery();
            }
        });
        click(R.id.btn_library, new Runnable() {
            @Override
            public void run() {
                showLibrary();
            }
        });
        click(R.id.btn_settings, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        click(R.id.btn_login, new Runnable() {
            @Override
            public void run() {
                login();
            }
        });
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                if (action == EditorInfo.IME_ACTION_SEARCH || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    submitQuery();
                    return true;
                }
                return false;
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    long d = EngineState.get().durationMs;
                    time.setText(fmt(d * p / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                long d = EngineState.get().durationMs;
                NativeEngine.command("seek", "position_ms", d * s.getProgress() / 1000);
            }
        });
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.setVolume(s.getProgress());
                NativeEngine.command("set_volume", "volume", s.getProgress());
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlayerService.uiVisible = true;
        EngineState s = EngineState.get();
        s.addObserver(this);
        ensureEngine();
        render(s);
    }

    @Override
    protected void onStop() {
        PlayerService.uiVisible = false;
        EngineState.get().removeObserver(this);
        handler.removeCallbacks(tick);
        super.onStop();
    }

    // ------------------------------------------------------------------ motor

    private boolean hasSession() {
        return NativeEngine.hasCredentials(getFilesDir().getAbsolutePath());
    }

    private void ensureEngine() {
        EngineState s = EngineState.get();
        if (!s.running && hasSession()) {
            PlayerService.start(this, null);
        }
    }

    private void login() {
        loginError.setText("");
        SpotifyAuth.login(this, new SpotifyAuth.Callback() {
            @Override
            public void onToken(String accessToken) {
                PlayerService.start(MainActivity.this, accessToken);
            }

            @Override
            public void onError(String message) {
                loginError.setText(message);
            }
        });
    }

    private void handleIntent(Intent i) {
        if (i == null) {
            return;
        }
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            text = i.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            text = i.getData().toString();
        }
        String uri = SpotifyLinks.toUri(text);
        if (uri != null) {
            playUri(uri);
        }
    }

    private void playUri(String uri) {
        if (EngineState.get().loggedIn) {
            NativeEngine.command("play", "uri", uri);
        } else {
            pendingUri = uri; // se reproduce cuando el motor termine de conectar
            ensureEngine();
        }
    }

    private void cycleRepeat(String current) {
        // off -> context -> track -> off (mismo orden de comandos que documenta Soloist)
        if ("off".equals(current)) {
            NativeEngine.command("set_repeat_track", "enabled", false);
            NativeEngine.command("set_repeat_context", "enabled", true);
        } else if ("context".equals(current)) {
            NativeEngine.command("set_repeat_context", "enabled", false);
            NativeEngine.command("set_repeat_track", "enabled", true);
        } else {
            NativeEngine.command("set_repeat_track", "enabled", false);
            NativeEngine.command("set_repeat_context", "enabled", false);
        }
    }

    // ------------------------------------------------------------------ búsqueda

    private void submitQuery() {
        String q = query.getText().toString().trim();
        if (q.length() == 0) {
            return;
        }
        hideKeyboard();
        String uri = SpotifyLinks.toUri(q);
        if (uri != null) {
            playUri(uri);
            query.setText("");
            return;
        }
        if (!EngineState.get().loggedIn) {
            return;
        }
        empty.setText("Buscando…");
        adapter.set(new ArrayList<WebApi.Item>());
        WebApi.get().search(q, results());
    }

    private void showLibrary() {
        if (!EngineState.get().loggedIn) {
            return;
        }
        empty.setText("Cargando biblioteca…");
        adapter.set(new ArrayList<WebApi.Item>());
        WebApi.get().myPlaylists(EngineState.get().username, results());
    }

    private WebApi.Result results() {
        return new WebApi.Result() {
            @Override
            public void onItems(List<WebApi.Item> items) {
                adapter.set(items);
                empty.setText(items.isEmpty() ? "Sin resultados" : "");
            }

            @Override
            public void onError(String message) {
                empty.setText("Error: " + message);
            }
        };
    }

    // ------------------------------------------------------------------ render

    @Override
    public void onStateChanged(EngineState s) {
        if (s.loggedIn && pendingUri != null) {
            NativeEngine.command("play", "uri", pendingUri);
            pendingUri = null;
        }
        render(s);
    }

    private void render(EngineState s) {
        boolean showLogin = !s.loggedIn && !s.running;
        loginPanel.setVisibility(showLogin ? View.VISIBLE : View.GONE);
        if (showLogin && s.lastError != null) {
            loginError.setText(s.lastError);
        }

        String st;
        if (s.loggedIn) {
            st = "● " + (s.active ? "sonando aquí" : "conectado") + " · " + prefs.bitrate() + "k";
        } else if (s.running) {
            st = "○ conectando…";
        } else {
            st = "○ sin sesión";
        }
        status.setText(st);
        status.setTextColor(getResources().getColor(s.loggedIn ? R.color.accent : R.color.muted));

        title.setText(s.title.length() > 0 ? s.title : (s.loggedIn ? "Buscá algo para escuchar" : "—"));
        String sub = s.artist;
        if (s.album.length() > 0 && !s.album.equals(s.artist)) {
            sub = sub.length() > 0 ? sub + " · " + s.album : s.album;
        }
        subtitle.setText(sub);
        duration.setText(s.durationMs > 0 ? "/ " + fmt(s.durationMs) : "");
        badge.setText("buffering".equals(s.status) ? "BUF" : s.isPlaying() ? "PLAY" : "paused".equals(s.status) ? "PAUSE" : "");

        play.setImageResource(s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(getString(s.isPlaying() ? R.string.pause : R.string.play));
        tint(shuffle, s.shuffle);
        tint(repeat, !"off".equals(s.repeat));
        repeat.setImageResource("track".equals(s.repeat) ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        volume.setProgress(s.volume);

        boolean eco = prefs.eco();
        cover.setVisibility(eco ? View.GONE : View.VISIBLE);
        if (!eco && s.coverDefault != null && !s.coverDefault.equals(shownCover)) {
            shownCover = s.coverDefault;
            CoverLoader.into(cover, s.coverDefault);
        }

        updateClock(s);
        handler.removeCallbacks(tick);
        if (s.isPlaying()) {
            handler.postDelayed(tick, 1000 - (s.positionNow() % 1000));
        }
    }

    private void updateClock(EngineState s) {
        if (userSeeking) {
            return;
        }
        long pos = s.positionNow();
        time.setText(fmt(pos));
        seek.setProgress(s.durationMs > 0 ? (int) (pos * 1000 / s.durationMs) : 0);
    }

    private void tint(ImageButton b, boolean on) {
        b.setImageTintList(ColorStateList.valueOf(getResources().getColor(on ? R.color.accent : R.color.muted)));
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private void click(int id, final Runnable r) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
    }

    private static Runnable cmd(final String name) {
        return new Runnable() {
            @Override
            public void run() {
                NativeEngine.command(name);
            }
        };
    }

    private final class Adapter extends BaseAdapter {
        final List<WebApi.Item> items = new ArrayList<>();

        void set(List<WebApi.Item> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_result, parent, false);
            }
            WebApi.Item it = items.get(i);
            ((TextView) v.findViewById(android.R.id.text1)).setText(it.title);
            ((TextView) v.findViewById(android.R.id.text2)).setText(it.subtitle);
            return v;
        }
    }
}
