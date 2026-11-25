package com.spotifast.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
    private TextView status, time, duration, badge, title, subtitle, empty, loginError, pairInfo;
    private android.widget.Button pairButton;
    private ImageView cover;
    private ImageButton play, shuffle, repeat;
    private SeekBar seek, volume;
    private View loginPanel;
    private android.widget.EditText query;
    private Browser browser;
    private ImageButton like;
    private ImageButton miniLike;
    private View searchBar, chips;
    private static final int TAB_HOME = 0, TAB_SEARCH = 1, TAB_LIBRARY = 2;
    private int tab = -1;
    private String libraryFilter = "playlists";
    private int miniColor;
    private View mini, nowPlaying;
    private ImageView miniCover;
    private TextView miniTitle, miniArtist;
    private ImageButton miniPlay;
    private android.widget.ProgressBar miniProgress;
    private String shownMiniCover;
    /** Estado de Me gusta de la pista actual (null = desconocido). */
    private Boolean liked;
    private String likedUri;
    private boolean userSeeking;
    private String pendingUri;
    private String shownCover;
    private String shownUri;

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
        pairInfo = (TextView) findViewById(R.id.pair_info);
        pairButton = (android.widget.Button) findViewById(R.id.btn_pair);
        query = (android.widget.EditText) findViewById(R.id.query);

        browser = new Browser(this, (ListView) findViewById(R.id.list), empty,
                (TextView) findViewById(R.id.page_title), findViewById(R.id.page_back));
        like = (ImageButton) findViewById(R.id.btn_like);
        miniLike = (ImageButton) findViewById(R.id.mini_like);
        searchBar = findViewById(R.id.search_bar);
        chips = findViewById(R.id.chips);
        browser.setListener(new Browser.Listener() {
            @Override
            public void onSearchAgain(String q) {
                query.setText(q);
                query.setSelection(q.length());
            }
        });
        click(R.id.nav_home, new Runnable() {
            @Override
            public void run() {
                showTab(TAB_HOME);
            }
        });
        click(R.id.nav_search, new Runnable() {
            @Override
            public void run() {
                showTab(TAB_SEARCH);
                query.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                imm.showSoftInput(query, 0);
            }
        });
        click(R.id.nav_library, new Runnable() {
            @Override
            public void run() {
                showTab(TAB_LIBRARY);
            }
        });
        click(R.id.nav_create, new Runnable() {
            @Override
            public void run() {
                createPlaylist();
            }
        });
        for (final String[] chip : new String[][]{{"playlists", ""}, {"albums", ""}, {"artists", ""}}) {
            int id = "playlists".equals(chip[0]) ? R.id.chip_playlists
                    : "albums".equals(chip[0]) ? R.id.chip_albums : R.id.chip_artists;
            click(id, new Runnable() {
                @Override
                public void run() {
                    libraryFilter = chip[0];
                    showTab(TAB_LIBRARY);
                }
            });
        }
        click(R.id.mini_like, new Runnable() {
            @Override
            public void run() {
                toggleLike();
            }
        });
        click(R.id.np_queue, new Runnable() {
            @Override
            public void run() {
                showNowPlaying(false);
                browser.showQueue();
            }
        });
        mini = findViewById(R.id.mini);
        nowPlaying = findViewById(R.id.now_playing);
        miniCover = (ImageView) findViewById(R.id.mini_cover);
        miniTitle = (TextView) findViewById(R.id.mini_title);
        miniArtist = (TextView) findViewById(R.id.mini_artist);
        miniPlay = (ImageButton) findViewById(R.id.mini_play);
        miniProgress = (android.widget.ProgressBar) findViewById(R.id.mini_progress);
        click(R.id.mini, new Runnable() {
            @Override
            public void run() {
                showNowPlaying(true);
            }
        });
        click(R.id.np_close, new Runnable() {
            @Override
            public void run() {
                showNowPlaying(false);
            }
        });
        click(R.id.mini_play, new Runnable() {
            @Override
            public void run() {
                NativeEngine.command(EngineState.get().isPlaying() ? "pause" : "play");
            }
        });
        // Carátula cuadrada que deja entrar todos los controles sin scroll: en 480x800
        // (típico de Android 4.4) queda más chica, en pantallas grandes ocupa el ancho.
        cover.post(new Runnable() {
            @Override
            public void run() {
                float d = getResources().getDisplayMetrics().density;
                int controls = (int) (400 * d); // encabezado + LCD + título + barra + botones + volumen
                int free = nowPlaying.getHeight() > 0 ? nowPlaying.getHeight()
                        : getResources().getDisplayMetrics().heightPixels;
                int size = Math.max((int) (120 * d), Math.min(cover.getWidth(), free - controls));
                ViewGroupCompat.square(cover, size);
            }
        });
        click(R.id.btn_like, new Runnable() {
            @Override
            public void run() {
                toggleLike();
            }
        });
        // Tocar el título abre el álbum; el artista/álbum de abajo, el artista.
        click(R.id.title, new Runnable() {
            @Override
            public void run() {
                showNowPlaying(false);
                browser.openNowPlaying(false);
            }
        });
        click(R.id.subtitle, new Runnable() {
            @Override
            public void run() {
                showNowPlaying(false);
                browser.openNowPlaying(true);
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
        click(R.id.btn_pair, new Runnable() {
            @Override
            public void run() {
                loginError.setText("");
                PlayerService.pair(MainActivity.this, !EngineState.get().pairing);
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
        if (Demo.requested(this, getIntent())) {
            Demo.fill(this, browser);
        }
    }

    static final int DEMO_HOME = 0, DEMO_SEARCH = 1, DEMO_LIBRARY = 2;

    void demoTab(int t) {
        styleTab(t);
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
        onStateChanged(s);
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
        if (tab != TAB_SEARCH) {
            showTab(TAB_SEARCH);
        }
        browser.showSearch(q);
    }

    /** Barra inferior: Inicio · Buscar · Tu biblioteca (como Spotify). */
    private void showTab(int t) {
        if (!EngineState.get().loggedIn) {
            return;
        }
        showNowPlaying(false);
        styleTab(t);
        switch (t) {
            case TAB_SEARCH:
                browser.showSearchRoot();
                break;
            case TAB_LIBRARY:
                browser.showLibrary(libraryFilter);
                break;
            default:
                browser.showHome();
        }
    }

    /** Solo el aspecto de la barra inferior y los controles de cada pestaña. */
    void styleTab(int t) {
        tab = t;
        searchBar.setVisibility(t == TAB_SEARCH ? View.VISIBLE : View.GONE);
        chips.setVisibility(t == TAB_LIBRARY ? View.VISIBLE : View.GONE);
        if (t != TAB_SEARCH) {
            hideKeyboard();
        }
        int[][] nav = {
                {R.id.nav_home_icon, R.id.nav_home_label},
                {R.id.nav_search_icon, R.id.nav_search_label},
                {R.id.nav_library_icon, R.id.nav_library_label}};
        for (int i = 0; i < nav.length; i++) {
            int c = getResources().getColor(i == t ? R.color.text : R.color.muted);
            ((ImageView) findViewById(nav[i][0])).setColorFilter(c);
            ((TextView) findViewById(nav[i][1])).setTextColor(c);
        }
        int[] chipIds = {R.id.chip_playlists, R.id.chip_albums, R.id.chip_artists};
        String[] keys = {"playlists", "albums", "artists"};
        for (int i = 0; i < chipIds.length; i++) {
            TextView c = (TextView) findViewById(chipIds[i]);
            boolean on = keys[i].equals(libraryFilter);
            c.setBackgroundResource(on ? R.drawable.tab_on : R.drawable.tab_off);
            c.setTextColor(getResources().getColor(on ? R.color.bg : R.color.text));
        }
    }

    /** Crear: nueva playlist (lo único de ese menú que la Web API permite). */
    private void createPlaylist() {
        if (!EngineState.get().loggedIn) {
            return;
        }
        final android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("Mi playlist");
        name.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Nueva playlist")
                .setView(name)
                .setPositiveButton("Crear", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        WebApi.get(MainActivity.this).createPlaylist(n.length() == 0 ? "Mi playlist" : n,
                                new WebApi.Created() {
                                    @Override
                                    public void onCreated(WebApi.Item playlist, String error) {
                                        if (playlist == null) {
                                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                                            return;
                                        }
                                        showTab(TAB_LIBRARY);
                                        browser.openPlaylist(playlist);
                                    }
                                });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Tiñe el mini reproductor con el tono de la carátula (como Spotify). 0 = color neutro. */
    private void tintMini(int color) {
        if (color == miniColor && color != 0) {
            return;
        }
        miniColor = color;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(8 * getResources().getDisplayMetrics().density);
        bg.setColor(color == 0 ? getResources().getColor(R.color.panel_hi) : color);
        mini.setBackgroundDrawable(bg);
    }

    /** Ayudante mínimo para centrar la carátula con un tamaño fijo. */
    private static final class ViewGroupCompat {
        static void square(View v, int size) {
            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) v.getLayoutParams();
            lp.width = size;
            lp.height = size;
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            v.setLayoutParams(lp);
        }
    }

    private void showNowPlaying(boolean show) {
        nowPlaying.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            hideKeyboard();
            render(EngineState.get()); // carga la carátula grande recién ahora
        }
    }

    @Override
    public void onBackPressed() {
        if (nowPlaying.getVisibility() == View.VISIBLE) {
            showNowPlaying(false);
        } else if (!browser.back()) {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ Me gusta

    private void toggleLike() {
        final String uri = EngineState.get().uri;
        if (uri.length() == 0 || liked == null) {
            return;
        }
        final boolean target = !liked;
        liked = target; // optimista: se corrige si falla
        renderLike();
        WebApi.get(this).save(uri, target, new WebApi.Done() {
            @Override
            public void onDone(boolean ok, String message) {
                if (!ok && uri.equals(likedUri)) {
                    liked = !target;
                    renderLike();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Un solo pedido por canción, y solo con la app a la vista. */
    private void refreshLike(EngineState s) {
        if (!s.loggedIn || s.uri.length() == 0 || s.uri.equals(likedUri)) {
            return;
        }
        final String uri = s.uri;
        likedUri = uri;
        liked = null;
        renderLike();
        WebApi.get(this).contains(uri, new WebApi.Flags() {
            @Override
            public void onFlags(boolean[] v) {
                if (uri.equals(likedUri) && v.length > 0) {
                    liked = v[0];
                    renderLike();
                }
            }
        });
    }

    private void renderLike() {
        boolean on = Boolean.TRUE.equals(liked);
        for (ImageButton b : new ImageButton[]{like, miniLike}) {
            b.setVisibility(liked == null ? View.INVISIBLE : View.VISIBLE);
            b.setImageResource(on ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
            b.setColorFilter(getResources().getColor(on ? R.color.accent : R.color.text));
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void onStateChanged(EngineState s) {
        if (s.loggedIn && browser.isEmpty()) {
            showTab(TAB_HOME); // pantalla inicial al conectar
        }
        if (!s.uri.equals(shownUri)) {
            shownUri = s.uri;
            browser.refreshHighlight();
        }
        refreshLike(s);
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
        pairInfo.setVisibility(s.pairing ? View.VISIBLE : View.GONE);
        pairInfo.setText(getString(R.string.pair_info, prefs.deviceName()));
        pairButton.setText(s.pairing ? R.string.pair_cancel : R.string.pair);

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

        title.setText(s.title.length() > 0 ? s.title : "—");
        String sub = s.artist;
        if (s.album.length() > 0 && !s.album.equals(s.artist)) {
            sub = sub.length() > 0 ? sub + " · " + s.album : s.album;
        }
        subtitle.setText(sub);
        duration.setText(s.durationMs > 0 ? "/ " + fmt(s.durationMs) : "");
        badge.setText("buffering".equals(s.status) ? "BUF" : s.isPlaying() ? "PLAY" : "paused".equals(s.status) ? "PAUSE" : "");

        play.setImageResource(s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        miniPlay.setImageResource(s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        mini.setVisibility(s.loggedIn && s.title.length() > 0 ? View.VISIBLE : View.GONE);
        if (mini.getVisibility() == View.GONE && nowPlaying.getVisibility() == View.VISIBLE) {
            showNowPlaying(false);
        }
        miniTitle.setText(s.title);
        miniArtist.setText(s.artist);
        play.setContentDescription(getString(s.isPlaying() ? R.string.pause : R.string.play));
        tint(shuffle, s.shuffle);
        tint(repeat, !"off".equals(s.repeat));
        repeat.setImageResource("track".equals(s.repeat) ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        volume.setProgress(s.volume);

        // Imágenes: 64 px en el mini reproductor, 300 px solo en "Sonando". Modo ahorro: ninguna.
        boolean eco = prefs.eco();
        cover.setVisibility(eco ? View.GONE : View.VISIBLE);
        if (!eco && s.coverDefault != null && !s.coverDefault.equals(shownCover)
                && nowPlaying.getVisibility() == View.VISIBLE) {
            shownCover = s.coverDefault;
            CoverLoader.into(cover, s.coverDefault);
        }
        if (eco || s.coverSmall == null) {
            shownMiniCover = null;
            tintMini(0);
            int p = (int) (12 * getResources().getDisplayMetrics().density);
            miniCover.setPadding(p, p, p, p);
            miniCover.setImageResource(R.drawable.ic_note);
            miniCover.setColorFilter(getResources().getColor(R.color.panel_edge));
        } else if (!s.coverSmall.equals(shownMiniCover)) {
            shownMiniCover = s.coverSmall;
            miniCover.setPadding(0, 0, 0, 0);
            miniCover.clearColorFilter();
            final String url = s.coverSmall;
            miniCover.setImageDrawable(null);
            CoverLoader.load(url, new CoverLoader.Cb() {
                @Override
                public void onBitmap(android.graphics.Bitmap b) {
                    if (url.equals(shownMiniCover)) {
                        miniCover.setImageBitmap(b);
                        tintMini(CoverLoader.tone(b));
                    }
                }
            });
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
        int progress = s.durationMs > 0 ? (int) (pos * 1000 / s.durationMs) : 0;
        miniProgress.setProgress(progress);
        if (nowPlaying.getVisibility() == View.VISIBLE) { // no dibujar lo que no se ve
            time.setText(fmt(pos));
            seek.setProgress(progress);
        }
    }

    private void tint(ImageButton b, boolean on) {
        // setColorFilter en vez de setImageTintList (API 21): funciona en Android 4.4.
        b.setColorFilter(getResources().getColor(on ? R.color.accent : R.color.muted));
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
}
