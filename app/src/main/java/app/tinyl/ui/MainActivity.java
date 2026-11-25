package app.tinyl.ui;

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

import app.tinyl.PlayerService;
import app.tinyl.Prefs;
import app.tinyl.R;
import app.tinyl.SpotifyAuth;
import app.tinyl.engine.EngineState;
import app.tinyl.engine.NativeEngine;
import app.tinyl.web.SpotifyLinks;
import app.tinyl.web.WebApi;

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
    private String homeFilter = "all";
    private ImageView avatar;
    private View homeChips, lyricsCard, lyricsView;
    private TextView lyricNow, lyricNext, lyricsTitle, lyricsSource;
    private ListView lyricsList;
    private LyricsAdapter lyricsAdapter;
    /** Letra de la canción actual (null = no hay / todavía no se pidió). */
    private app.tinyl.lyrics.Lyrics lyrics;
    private String lyricsUri;
    private int lyricActive = -2;
    private long lyricsTouchedAt;
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
                // 1 vez por segundo; con la letra a la vista, 3 por segundo para seguir el canto.
                handler.postDelayed(this, lyricsView.getVisibility() == View.VISIBLE ? 300 : 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        // Zonas seguras (Android 15 dibuja de borde a borde): cada capa se corre lo que tapan las barras.
        EdgeToEdge.apply(this, findViewById(android.R.id.content),
                findViewById(R.id.main_column), findViewById(R.id.now_playing), findViewById(R.id.lyrics_view));
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
        final String[] libKeys = {"playlists", "albums", "artists", "shows"};
        final int[] libIds = {R.id.chip_playlists, R.id.chip_albums, R.id.chip_artists, R.id.chip_shows};
        for (int i = 0; i < libKeys.length; i++) {
            final String key = libKeys[i];
            click(libIds[i], new Runnable() {
                @Override
                public void run() {
                    libraryFilter = key;
                    showTab(TAB_LIBRARY);
                }
            });
        }
        final String[] homeKeys = {"all", "music", "podcasts"};
        final int[] homeIds = {R.id.chip_all, R.id.chip_music, R.id.chip_podcasts};
        for (int i = 0; i < homeKeys.length; i++) {
            final String key = homeKeys[i];
            click(homeIds[i], new Runnable() {
                @Override
                public void run() {
                    homeFilter = key;
                    showTab(TAB_HOME);
                }
            });
        }
        avatar = (ImageView) findViewById(R.id.avatar);
        Round.clip(avatar, -1);
        browser.setAvatar(avatar);
        homeChips = findViewById(R.id.home_chips);
        lyricsCard = findViewById(R.id.lyrics_card);
        lyricsView = findViewById(R.id.lyrics_view);
        lyricNow = (TextView) findViewById(R.id.lyric_now);
        lyricNext = (TextView) findViewById(R.id.lyric_next);
        lyricsTitle = (TextView) findViewById(R.id.lyrics_title);
        lyricsSource = (TextView) findViewById(R.id.lyrics_source);
        lyricsList = (ListView) findViewById(R.id.lyrics_list);
        lyricsAdapter = new LyricsAdapter();
        lyricsList.setAdapter(lyricsAdapter);
        lyricsList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                // Tocar una línea sincronizada salta a ese momento de la canción.
                if (lyrics != null && lyrics.synced && pos < lyrics.lines.size()) {
                    NativeEngine.command("seek", "position_ms", lyrics.lines.get(pos).atMs);
                }
            }
        });
        lyricsList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView v, int state) {
                if (state != SCROLL_STATE_IDLE) {
                    lyricsTouchedAt = android.os.SystemClock.elapsedRealtime(); // no pelearle al dedo
                }
            }

            @Override
            public void onScroll(android.widget.AbsListView v, int a, int b, int c) {
            }
        });
        click(R.id.lyrics_card, new Runnable() {
            @Override
            public void run() {
                showLyrics(true);
            }
        });
        click(R.id.lyrics_close, new Runnable() {
            @Override
            public void run() {
                showLyrics(false);
            }
        });
        click(R.id.avatar, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        click(R.id.mini_device, new Runnable() {
            @Override
            public void run() {
                pickDevice();
            }
        });
        click(R.id.np_devices, new Runnable() {
            @Override
            public void run() {
                pickDevice();
            }
        });
        click(R.id.np_timer, new Runnable() {
            @Override
            public void run() {
                pickSleep();
            }
        });
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

    /** Modo demo: letra de ejemplo para revisar la pantalla sin cuenta. */
    void demoLyrics(app.tinyl.lyrics.Lyrics l) {
        lyricsUri = EngineState.get().uri;
        lyrics = l;
        lyricsCard.setVisibility(View.VISIBLE);
        lyricsSource.setText("Letra: demo · sincronizada");
        lyricsAdapter.notifyDataSetChanged();
    }

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
                browser.showHome(homeFilter);
        }
    }

    /** Solo el aspecto de la barra inferior y los controles de cada pestaña. */
    void styleTab(int t) {
        tab = t;
        searchBar.setVisibility(t == TAB_SEARCH ? View.VISIBLE : View.GONE);
        chips.setVisibility(t == TAB_LIBRARY ? View.VISIBLE : View.GONE);
        homeChips.setVisibility(t == TAB_HOME ? View.VISIBLE : View.GONE);
        // "Baño" de color tenue arriba del Inicio (un gradiente: no cuesta nada dibujarlo).
        findViewById(R.id.main_column).setBackgroundResource(t == TAB_HOME ? R.drawable.home_wash : 0);
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
        int[] chipIds = {R.id.chip_playlists, R.id.chip_albums, R.id.chip_artists, R.id.chip_shows,
                R.id.chip_all, R.id.chip_music, R.id.chip_podcasts};
        String[] keys = {"playlists", "albums", "artists", "shows", "all", "music", "podcasts"};
        for (int i = 0; i < chipIds.length; i++) {
            TextView c = (TextView) findViewById(chipIds[i]);
            boolean on = keys[i].equals(i < 4 ? libraryFilter : homeFilter);
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

    // ------------------------------------------------------------------ letras

    private void loadLyrics(final EngineState s) {
        if (s.uri.equals(lyricsUri) || !s.loggedIn) {
            return;
        }
        lyricsUri = s.uri;
        lyrics = null;
        lyricActive = -2;
        lyricsCard.setVisibility(View.GONE);
        lyricsAdapter.notifyDataSetChanged();
        app.tinyl.lyrics.Lyrics.load(this, s.uri, s.coverDefault != null ? s.coverDefault : s.coverSmall,
                s.title, s.artist, s.album, s.durationMs, new app.tinyl.lyrics.Lyrics.Cb() {
                    @Override
                    public void onLyrics(String uri, app.tinyl.lyrics.Lyrics l, String source) {
                        if (!uri.equals(lyricsUri)) {
                            return; // ya cambió la canción
                        }
                        lyrics = l;
                        lyricActive = -2;
                        lyricsCard.setVisibility(l == null ? View.GONE : View.VISIBLE);
                        lyricsSource.setText(l == null ? "" : "Letra: " + source
                                + (l.synced ? " · sincronizada" : ""));
                        lyricsAdapter.notifyDataSetChanged();
                        updateLyric(EngineState.get().positionNow());
                    }
                });
    }

    private void showLyrics(boolean show) {
        if (show && lyrics == null) {
            return;
        }
        lyricsView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            EngineState s = EngineState.get();
            lyricsTitle.setText(s.title + (s.artist.length() > 0 ? " · " + s.artist : ""));
            lyricActive = -2;
            lyricsTouchedAt = 0;
            updateLyric(s.positionNow());
            handler.removeCallbacks(tick);
            if (s.isPlaying()) {
                handler.post(tick);
            }
        }
    }

    /** Actualiza la vista previa y, si está abierta, resalta y sigue la línea que se canta. */
    private void updateLyric(long pos) {
        if (lyrics == null) {
            return;
        }
        int a = lyrics.activeLine(pos);
        if (a == lyricActive) {
            return;
        }
        lyricActive = a;
        if (lyrics.synced) {
            int cur = Math.max(0, a);
            lyricNow.setText(lyrics.lines.get(cur).text);
            lyricNext.setText(cur + 1 < lyrics.lines.size() ? lyrics.lines.get(cur + 1).text : "");
        } else {
            lyricNow.setText(lyrics.lines.get(0).text);
            lyricNext.setText(lyrics.lines.size() > 1 ? lyrics.lines.get(1).text : "");
        }
        if (lyricsView.getVisibility() == View.VISIBLE) {
            lyricsAdapter.notifyDataSetChanged();
            boolean userScrolling = android.os.SystemClock.elapsedRealtime() - lyricsTouchedAt < 4000;
            if (a >= 0 && !userScrolling) {
                lyricsList.smoothScrollToPositionFromTop(a, lyricsList.getHeight() / 3, 300);
            }
        }
    }

    private final class LyricsAdapter extends android.widget.BaseAdapter {
        @Override
        public int getCount() {
            return lyrics == null ? 0 : lyrics.lines.size();
        }

        @Override
        public Object getItem(int i) {
            return lyrics.lines.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, android.view.ViewGroup parent) {
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_lyric, parent, false);
            }
            TextView t = (TextView) v;
            t.setText(lyrics.lines.get(i).text);
            int color;
            if (!lyrics.synced) {
                color = R.color.text;
            } else if (i == lyricActive) {
                color = R.color.accent; // la línea que se canta
            } else {
                color = i < lyricActive ? R.color.muted : R.color.text;
            }
            t.setTextColor(getResources().getColor(color));
            t.setAlpha(lyrics.synced && i > lyricActive + 0 && i != lyricActive ? 0.55f : 1f);
            return v;
        }
    }

    // ------------------------------------------------------------------ Spotify Connect

    /** Elegir dónde suena: este teléfono u otro dispositivo de tu cuenta (PC, parlante, TV…). */
    private void pickDevice() {
        if (!EngineState.get().loggedIn) {
            return;
        }
        final ProgressToast wait = new ProgressToast("Buscando dispositivos…");
        WebApi.get(this).devices(new WebApi.Devices() {
            @Override
            public void onDevices(final List<WebApi.Device> found, String error) {
                wait.cancel();
                if (error != null) {
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                    return;
                }
                final String me = prefs.deviceName();
                final List<WebApi.Device> list = new ArrayList<>(found);
                android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
                    @Override
                    public int getCount() {
                        return list.size();
                    }

                    @Override
                    public Object getItem(int i) {
                        return list.get(i);
                    }

                    @Override
                    public long getItemId(int i) {
                        return i;
                    }

                    @Override
                    public View getView(int i, View v, android.view.ViewGroup parent) {
                        if (v == null) {
                            v = getLayoutInflater().inflate(R.layout.item_result, parent, false);
                        }
                        WebApi.Device d = list.get(i);
                        boolean mine = d.name.equals(me);
                        TextView t1 = (TextView) v.findViewById(android.R.id.text1);
                        TextView t2 = (TextView) v.findViewById(android.R.id.text2);
                        ImageView icon = (ImageView) v.findViewById(android.R.id.icon);
                        t1.setText(mine ? "Este teléfono" : d.name);
                        t1.setTextColor(getResources().getColor(d.active ? R.color.accent : R.color.text));
                        t2.setText(d.active ? "Sonando acá" : mine ? d.name : deviceType(d.type));
                        String ty = d.type.toLowerCase(java.util.Locale.US);
                        icon.setImageResource(ty.contains("computer") ? R.drawable.ic_computer
                                : ty.contains("tv") || ty.contains("cast") ? R.drawable.ic_tv
                                : ty.contains("smartphone") || ty.contains("tablet") ? R.drawable.ic_phone
                                : R.drawable.ic_devices);
                        int p = (int) (12 * getResources().getDisplayMetrics().density);
                        icon.setPadding(p, p, p, p);
                        icon.setColorFilter(getResources().getColor(d.active ? R.color.accent : R.color.muted));
                        return v;
                    }
                };
                if (list.isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            "No hay otros dispositivos con Spotify abierto en tu cuenta", Toast.LENGTH_LONG).show();
                    return;
                }
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.devices)
                        .setAdapter(adapter, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dlg, int which) {
                                transferTo(list.get(which), list.get(which).name.equals(me));
                            }
                        })
                        .show();
            }
        });
    }

    private static String deviceType(String t) {
        switch (t.toLowerCase(java.util.Locale.US)) {
            case "computer":
                return "Computadora";
            case "smartphone":
                return "Celular";
            case "tablet":
                return "Tablet";
            case "speaker":
                return "Parlante";
            case "tv":
                return "TV";
            case "castvideo":
            case "castaudio":
                return "Google Cast";
            default:
                return "Spotify Connect";
        }
    }

    private void transferTo(final WebApi.Device d, final boolean mine) {
        WebApi.get(this).transfer(d.id, new WebApi.Done() {
            @Override
            public void onDone(boolean ok, String message) {
                if (!ok && mine) {
                    NativeEngine.command("activate"); // traerla acá aunque la Web API no responda
                    NativeEngine.command("play");
                    ok = true;
                }
                if (!ok) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    return;
                }
                EngineState s = EngineState.get();
                s.remoteDevice = mine ? "" : d.name;
                s.changed();
            }
        });
    }

    // ------------------------------------------------------------------ temporizador

    private void pickSleep() {
        final EngineState s = EngineState.get();
        final boolean on = s.sleepAt > 0 || s.sleepEndOfTrack;
        final String[] labels = on
                ? new String[]{"Apagar temporizador", "15 minutos", "30 minutos", "45 minutos", "1 hora", "Al terminar esta canción"}
                : new String[]{"15 minutos", "30 minutos", "45 minutos", "1 hora", "Al terminar esta canción"};
        final int[] minutes = on ? new int[]{0, 15, 30, 45, 60, -1} : new int[]{15, 30, 45, 60, -1};
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.sleep_timer)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        PlayerService.sleep(MainActivity.this, minutes[which]);
                        Toast.makeText(MainActivity.this, minutes[which] == 0 ? "Temporizador apagado"
                                : minutes[which] < 0 ? "Se pausa al terminar la canción"
                                : "Se pausa en " + minutes[which] + " min", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** Aviso corto mientras se espera una respuesta. */
    private final class ProgressToast {
        private final Toast t;

        ProgressToast(String text) {
            t = Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT);
            t.show();
        }

        void cancel() {
            t.cancel();
        }
    }

    // ------------------------------------------------------------------ avatar

    private boolean avatarLoaded;

    /** Tu foto de Spotify en el encabezado (un pedido por sesión; la URL queda guardada). */
    private void loadAvatar() {
        if (avatarLoaded) {
            return;
        }
        avatarLoaded = true;
        String cached = prefs.avatarUrl();
        if (cached.length() > 0 && !prefs.eco()) {
            CoverLoader.into(avatar, cached);
        }
        WebApi.get(this).me(new WebApi.Profile() {
            @Override
            public void onProfile(String name, String imageUrl) {
                if (imageUrl != null && imageUrl.length() > 0) {
                    prefs.setAvatarUrl(imageUrl);
                    if (!prefs.eco()) {
                        CoverLoader.into(avatar, imageUrl);
                    }
                }
            }
        });
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
        if (!show) {
            lyricsView.setVisibility(View.GONE);
        }
        if (show) {
            hideKeyboard();
            render(EngineState.get()); // carga la carátula grande recién ahora
            loadLyrics(EngineState.get()); // la letra solo se pide con "Sonando" a la vista
        }
    }

    @Override
    public void onBackPressed() {
        if (lyricsView.getVisibility() == View.VISIBLE) {
            showLyrics(false);
        } else if (nowPlaying.getVisibility() == View.VISIBLE) {
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
        if (s.loggedIn) {
            loadAvatar();
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
        if (s.loggedIn && s.remoteDevice.length() > 0) {
            st = "● " + s.remoteDevice;
        } else if (s.loggedIn) {
            st = "● " + prefs.bitrate() + " kbps";
        } else if (s.running) {
            st = "○ conectando…";
        } else {
            st = "○ sin sesión";
        }
        status.setText(st);
        status.setTextColor(getResources().getColor(s.loggedIn ? R.color.accent : R.color.muted));
        TextView npStatus = (TextView) findViewById(R.id.status_np);
        if (s.sleepAt > 0) {
            long min = Math.max(1, (s.sleepAt - android.os.SystemClock.elapsedRealtime() + 59_999) / 60_000);
            npStatus.setText("SE PAUSA EN " + min + " MIN");
        } else if (s.sleepEndOfTrack) {
            npStatus.setText("SE PAUSA AL TERMINAR");
        } else if (s.remoteDevice.length() > 0) {
            npStatus.setText("EN " + s.remoteDevice.toUpperCase());
        } else {
            npStatus.setText(R.string.now_playing);
        }
        ((ImageButton) findViewById(R.id.np_timer)).setColorFilter(getResources().getColor(
                s.sleepAt > 0 || s.sleepEndOfTrack ? R.color.accent : R.color.text));
        int devColor = getResources().getColor(s.remoteDevice.length() > 0 ? R.color.accent : R.color.text);
        ((ImageButton) findViewById(R.id.np_devices)).setColorFilter(devColor);
        ((ImageButton) findViewById(R.id.mini_device)).setColorFilter(devColor);
        if (nowPlaying.getVisibility() == View.VISIBLE && !s.uri.equals(lyricsUri)) {
            loadLyrics(s);
        }

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
        miniArtist.setText(s.remoteDevice.length() > 0 ? "Sonando en " + s.remoteDevice : s.artist);
        miniArtist.setTextColor(getResources().getColor(s.remoteDevice.length() > 0 ? R.color.accent : R.color.muted));
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
            // Sin carátula: el logo de Tinyl en vez de una nota genérica.
            miniCover.setPadding(0, 0, 0, 0);
            miniCover.clearColorFilter();
            miniCover.setImageResource(R.drawable.ic_launcher);
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
            updateLyric(pos);
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
