package app.tinyl.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import app.tinyl.R;
import app.tinyl.engine.EngineState;
import app.tinyl.engine.NativeEngine;
import app.tinyl.web.WebApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Navegación sin fragments ni librerías: una pila de pantallas (búsqueda, biblioteca, playlist,
 * álbum, artista, cola) sobre un único ListView. Cada pantalla pide su primera página al abrirse
 * y las siguientes solo con "Cargar más".
 */
final class Browser {

    /** Qué carga una pantalla. */
    interface Loader {
        void load(WebApi api, WebApi.Result r);
    }

    private static final class Page {
        final String title;
        /** Contexto para reproducir (playlist/álbum/artista/colección); null = pistas sueltas. */
        final String contextUri;
        final Loader loader;
        final List<WebApi.Item> items = new ArrayList<>();
        String next;
        boolean loading;
        String error;
        int scroll;
        /** Inicio: los atajos del principio se muestran en grilla de 2 columnas. */
        boolean grid;
        /** Filas fijas al principio (no cuentan como "ya cargado"). */
        int fixed;
        /** Pantalla de Cola: se recarga sola cuando cambia la canción. */
        boolean queue;
        /** Qué decir si la pantalla queda vacía (nunca una pantalla en blanco). */
        String emptyText = "Nada por acá";

        Page(String title, String contextUri, Loader loader) {
            this.title = title;
            this.contextUri = contextUri;
            this.loader = loader;
        }
    }

    // Filas especiales (no vienen de la API)
    private static final String PLAY_ALL = "play_all";
    private static final String SHUFFLE_ALL = "shuffle_all";
    private static final String MORE = "more";
    private static final String NAV = "nav";
    private static final String LIKED = "liked";
    private static final String QUERY = "query";
    private static final String CLEAR_HISTORY = "clear_history";
    private static final String PAIR = "pair";
    private static final String PREFETCH = "prefetch";
    private static final String CARDS = "cards";
    private static final String AUTOPLAY = "autoplay";
    private static final String RADIO = "radio";

    private final Activity activity;
    private final ListView list;
    private final TextView empty;
    private final TextView title;
    private final View back;
    private final WebApi api;
    private final app.tinyl.Prefs prefs;
    private final ArrayList<Page> stack = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    Browser(Activity activity, ListView list, TextView empty, TextView title, View back) {
        this.activity = activity;
        this.list = list;
        this.empty = empty;
        this.title = title;
        this.back = back;
        this.api = WebApi.get(activity);
        this.prefs = new app.tinyl.Prefs(activity);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onClick(adapter.rows.get(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                return onLongClick(adapter.rows.get(pos));
            }
        });
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                back();
            }
        });
    }

    // ================================================================ pantallas raíz

    /**
     * Inicio por filtro (Todo / Música / Podcasts): atajos a lo último que escuchaste, tus artistas
     * del momento en tarjetas, canciones recientes; en Podcasts, "Seguir escuchando" y tus shows.
     * Son 2 pedidos chicos por apertura; nada en segundo plano.
     */
    void showHome(final String filter) {
        final String user = EngineState.get().username;
        Page p = new Page(greeting(), null, new Loader() {
            @Override
            public void load(final WebApi api, final WebApi.Result r) {
                if ("podcasts".equals(filter)) {
                    podcastsHome(api, r);
                    return;
                }
                api.home(user, new WebApi.Result() {
                    @Override
                    public void onItems(List<WebApi.Item> items, String next) {
                        int h = 0;
                        while (h < items.size() && !items.get(h).isHeader()) {
                            h++;
                        }
                        final List<WebApi.Item> shortcuts = new ArrayList<>();
                        for (WebApi.Item it : items.subList(0, h)) {
                            if (!"music".equals(filter) || !"show".equals(it.kind)) {
                                shortcuts.add(it);
                            }
                        }
                        final List<WebApi.Item> rest = new ArrayList<>(items.subList(h, items.size()));
                        api.topArtists(6, new WebApi.Result() {
                            @Override
                            public void onItems(List<WebApi.Item> artists, String n) {
                                List<WebApi.Item> out = new ArrayList<>(shortcuts);
                                if (!artists.isEmpty()) {
                                    out.add(WebApi.Item.header("Tus artistas del momento"));
                                    for (WebApi.Item a : artists) {
                                        a.card = true;
                                    }
                                    out.addAll(artists);
                                }
                                out.addAll(rest);
                                r.onItems(out, null);
                            }

                            @Override
                            public void onError(String message) {
                                List<WebApi.Item> out = new ArrayList<>(shortcuts);
                                out.addAll(rest);
                                r.onItems(out, null);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        r.onError(message);
                    }
                });
            }
        });
        p.grid = true;
        p.emptyText = "Escuchá algo y tus favoritos van a aparecer acá";
        root(p);
    }

    /** Podcasts en el Inicio: episodios a medio escuchar + tus podcasts en tarjetas. */
    private void podcastsHome(final WebApi api, final WebApi.Result r) {
        api.savedEpisodes(new WebApi.Result() {
            @Override
            public void onItems(List<WebApi.Item> episodes, String next) {
                final List<WebApi.Item> out = new ArrayList<>();
                for (WebApi.Item e : episodes) {
                    if (e.resumeMs > 0 && !e.fullyPlayed && out.size() < 6) {
                        out.add(e);
                    }
                }
                if (!out.isEmpty()) {
                    out.add(0, WebApi.Item.header("Seguir escuchando"));
                }
                api.savedShows(new WebApi.Result() {
                    @Override
                    public void onItems(List<WebApi.Item> shows, String n) {
                        if (!shows.isEmpty()) {
                            out.add(WebApi.Item.header("Tus podcasts"));
                            for (WebApi.Item s : shows) {
                                s.card = true;
                            }
                            out.addAll(shows);
                        }
                        r.onItems(out, null);
                    }

                    @Override
                    public void onError(String message) {
                        r.onItems(out, null);
                    }
                });
            }

            @Override
            public void onError(String message) {
                r.onError(message);
            }
        });
    }

    static String greeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 5 && h < 12 ? "Buenos días" : h >= 12 && h < 20 ? "Buenas tardes" : "Buenas noches";
    }

    /** Buscar: búsquedas recientes (guardadas en el teléfono, cero red). */
    void showSearchRoot() {
        Page p = new Page("Buscar", null, null);
        List<String> history = prefs.searchHistory();
        if (!history.isEmpty()) {
            p.items.add(WebApi.Item.header("Búsquedas recientes"));
            for (String q : history) {
                p.items.add(WebApi.Item.make(q, q, "", QUERY));
            }
            p.items.add(WebApi.Item.make("", "Borrar búsquedas recientes", "", CLEAR_HISTORY));
        }
        root(p);
    }

    void showSearch(final String query) {
        prefs.addSearch(query);
        if (stack.size() > 1) {
            stack.subList(1, stack.size()).clear(); // una búsqueda nueva reemplaza a la anterior
        }
        push(new Page(query, null, new Loader() {
            @Override
            public void load(WebApi api, WebApi.Result r) {
                api.search(query, r);
            }
        }));
    }

    /** Biblioteca filtrada por chip: playlists (con Tus me gusta arriba), álbumes o artistas. */
    void showLibrary(final String filter) {
        Page p;
        if ("albums".equals(filter)) {
            p = new Page("Tu biblioteca", null, new Loader() {
                @Override
                public void load(WebApi api, WebApi.Result r) {
                    api.savedAlbums(r);
                }
            });
        } else if ("artists".equals(filter)) {
            p = new Page("Tu biblioteca", null, new Loader() {
                @Override
                public void load(WebApi api, WebApi.Result r) {
                    api.followedArtists(r);
                }
            });
        } else if ("shows".equals(filter)) {
            p = new Page("Tu biblioteca", null, new Loader() {
                @Override
                public void load(WebApi api, WebApi.Result r) {
                    api.savedShows(r);
                }
            });
            p.items.add(nav("Tus episodios", "episodes", "Episodios guardados"));
            p.fixed = 1;
            p.emptyText = "Seguí podcasts en Spotify y aparecen acá";
        } else {
            p = new Page("Tu biblioteca", null, new Loader() {
                @Override
                public void load(WebApi api, WebApi.Result r) {
                    api.myPlaylists(r);
                }
            });
            p.items.add(WebApi.Item.make("liked", "Tus me gusta", "Playlist · canciones guardadas", LIKED));
            p.items.add(nav("Escuchado recientemente", "recent", "Últimas 50"));
            p.fixed = 2;
        }
        root(p);
    }

    /** Cola, abierta desde "Sonando": autoplay, radio y lo que sigue (tocar = saltar ahí). */
    void showQueue() {
        if (!stack.isEmpty() && top().queue) {
            reloadQueue.run();
            return;
        }
        Page p = new Page("Cola", null, new Loader() {
            @Override
            public void load(WebApi api, WebApi.Result r) {
                api.queue(r);
            }
        });
        p.queue = true;
        p.emptyText = "La cola está vacía";
        queueRows(p);
        push(p);
    }

    /** Filas fijas de la Cola: interruptor de reproducción automática y radio. */
    private void queueRows(Page p) {
        if (p.loader == null) { // demo: conservar las filas de la cola, rehacer solo las fijas
            List<WebApi.Item> rest = new ArrayList<>(p.items.subList(Math.min(p.fixed, p.items.size()), p.items.size()));
            p.items.clear();
            addQueueRows(p);
            p.items.addAll(rest);
            return;
        }
        p.items.clear();
        addQueueRows(p);
    }

    private void addQueueRows(Page p) {
        boolean on = prefs.autoplay();
        p.items.add(WebApi.Item.make("", "Reproducción automática", on
                ? "Activada · después siguen similares"
                : "Desactivada · se detiene al terminar", AUTOPLAY));
        EngineState s = EngineState.get();
        if (s.uri.startsWith("spotify:track:")) {
            p.items.add(WebApi.Item.make(s.uri, "Iniciar radio",
                    "Canciones similares a «" + s.title + "»", RADIO));
        }
        p.fixed = p.items.size();
        p.next = null;
    }

    /** Recarga la Cola (con demora: al saltar varias canciones llegan varios cambios seguidos). */
    private final Runnable reloadQueue = new Runnable() {
        @Override
        public void run() {
            if (stack.isEmpty() || !top().queue) {
                return;
            }
            Page p = top();
            if (p.loader == null) {
                return; // modo demo
            }
            queueRows(p);
            load(p);
            render();
        }
    };

    /** Cola con filas ya armadas (modo demo). */
    void showQueueStatic(List<WebApi.Item> items) {
        Page p = new Page("Cola", null, null);
        p.queue = true;
        queueRows(p);
        p.items.addAll(items);
        push(p);
    }

    private void reloadQueueSoon() {
        list.removeCallbacks(reloadQueue);
        list.postDelayed(reloadQueue, 900);
    }

    /** Pantalla con filas ya armadas (modo demo para capturas). */
    void showStatic(String title, String contextUri, List<WebApi.Item> items, boolean asRoot) {
        showStatic(title, contextUri, items, asRoot, false);
    }

    void showStatic(String title, String contextUri, List<WebApi.Item> items, boolean asRoot, boolean grid) {
        Page p = new Page(title, contextUri, null);
        p.grid = grid;
        p.items.addAll(items);
        if (asRoot) {
            root(p);
        } else {
            push(p);
        }
    }

    boolean isEmpty() {
        return stack.isEmpty();
    }

    /** @return true si consumió el "atrás". */
    boolean back() {
        if (stack.size() <= 1) {
            return false;
        }
        stack.remove(stack.size() - 1);
        render();
        list.setSelection(top().scroll);
        return true;
    }

    // ================================================================ navegación

    private void root(Page p) {
        stack.clear();
        push(p);
    }

    private void push(Page p) {
        if (!stack.isEmpty()) {
            top().scroll = list.getFirstVisiblePosition();
        }
        stack.add(p);
        if (p.loader != null && p.items.size() <= p.fixed) {
            load(p);
        }
        render();
        list.setSelection(0);
    }

    private Page top() {
        return stack.get(stack.size() - 1);
    }

    private void load(final Page p) {
        p.loading = true;
        p.error = null;
        WebApi.Result r = new WebApi.Result() {
            @Override
            public void onItems(List<WebApi.Item> items, String next) {
                p.loading = false;
                p.items.addAll(items);
                p.next = next;
                if (p == top()) {
                    render();
                }
            }

            @Override
            public void onError(String message) {
                p.loading = false;
                p.error = message;
                if (p == top()) {
                    render();
                }
            }
        };
        if (p.items.size() <= p.fixed || p.next == null) {
            p.loader.load(api, r);
        } else {
            api.page(p.next, r);
        }
    }

    private void open(WebApi.Item it) {
        final String uri = it.uri;
        switch (it.kind) {
            case "playlist":
                push(new Page(it.title, uri, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.playlistItems(uri, r);
                    }
                }));
                break;
            case "album":
                push(new Page(it.title, uri, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.albumTracks(uri, r);
                    }
                }));
                break;
            case "artist":
                push(new Page(it.title, uri, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.artist(uri, r);
                    }
                }));
                break;
            case "show":
                push(new Page(it.title, null, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.showEpisodes(uri, r);
                    }
                }));
                break;
            default:
        }
    }

    private void openLibrarySection(String key) {
        switch (key) {
            case "episodes":
                push(new Page("Tus episodios", null, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.savedEpisodes(r);
                    }
                }));
                break;
            case "liked": {
                String user = EngineState.get().username;
                String ctx = user.length() > 0 ? "spotify:user:" + user + ":collection" : null;
                push(new Page("Tus me gusta", ctx, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.likedTracks(r);
                    }
                }));
                break;
            }
            case "playlists":
                push(new Page("Playlists", null, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.myPlaylists(r);
                    }
                }));
                break;
            case "albums":
                push(new Page("Álbumes", null, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.savedAlbums(r);
                    }
                }));
                break;
            case "artists":
                push(new Page("Artistas", null, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.followedArtists(r);
                    }
                }));
                break;
            case "recent":
                push(new Page("Escuchado recientemente", null, new Loader() {
                    @Override
                    public void load(WebApi api, WebApi.Result r) {
                        api.recentlyPlayed(r);
                    }
                }));
                break;
            default:
        }
    }

    // ================================================================ acciones

    private void onClick(WebApi.Item it) {
        Page p = top();
        switch (it.kind) {
            case NAV:
                openLibrarySection(it.uri);
                return;
            case LIKED:
                openLibrarySection("liked");
                return;
            case QUERY:
                if (listener != null) {
                    listener.onSearchAgain(it.uri);
                }
                showSearch(it.uri);
                return;
            case CLEAR_HISTORY:
                prefs.clearSearches();
                showSearchRoot();
                return;
            case MORE:
                load(p);
                render();
                return;
            case PLAY_ALL:
                NativeEngine.command("set_shuffle", "enabled", false);
                play(p.contextUri, null);
                return;
            case SHUFFLE_ALL:
                NativeEngine.command("set_shuffle", "enabled", true);
                play(p.contextUri, null);
                return;
            case PREFETCH:
                prefetch(p);
                return;
            case AUTOPLAY:
                prefs.setAutoplay(!prefs.autoplay());
                queueRows(p);
                if (p.loader != null) {
                    load(p); // al prenderla, Spotify suma similares a la cola
                }
                render();
                return;
            case RADIO:
                startRadio(it.uri);
                return;
            case "header":
                return;
            default:
        }
        if (p.queue && it.queuePos != -1) {
            if (it.queuePos >= 0) { // saltar hasta esa canción sin perder el resto de la cola
                NativeEngine.command("skip_next", "count", it.queuePos + 1);
                reloadQueueSoon();
            }
            return;
        }
        if ("episode".equals(it.kind)) {
            // Podcasts: retomar donde quedaste (salvo que ya lo hayas terminado).
            long pos = it.fullyPlayed ? 0 : Math.max(0, it.resumeMs - 3000);
            EngineState.get().contextUri = it.uri;
            NativeEngine.command("play", "uri", it.uri, "position_ms", pos);
            return;
        }
        if (it.isPlayable()) {
            // Dentro de un álbum/playlist: arranca en esta pista y sigue con el resto.
            if (p.contextUri != null && !p.contextUri.contains(":artist:")) {
                play(p.contextUri, it.uri);
            } else {
                play(it.uri, null);
            }
        } else {
            open(it);
        }
    }

    private boolean onLongClick(final WebApi.Item it) {
        if (it.isHeader() || NAV.equals(it.kind) || MORE.equals(it.kind) || LIKED.equals(it.kind)
                || PREFETCH.equals(it.kind) || AUTOPLAY.equals(it.kind) || RADIO.equals(it.kind)
                || QUERY.equals(it.kind) || CLEAR_HISTORY.equals(it.kind)
                || PLAY_ALL.equals(it.kind) || SHUFFLE_ALL.equals(it.kind)) {
            return false;
        }
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (it.isPlayable()) {
            labels.add("Agregar a la cola");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    api.addToQueue(it.uri, toast("Agregada a la cola"));
                }
            });
        } else {
            labels.add("Reproducir");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    play(it.uri, null);
                }
            });
        }
        if ("track".equals(it.kind)) {
            labels.add("Iniciar radio");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    startRadio(it.uri);
                }
            });
        }
        if (it.isPlayable()) {
            labels.add("Agregar a una playlist");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    pickPlaylist(it);
                }
            });
        }
        labels.add("Guardar en tu biblioteca / Me gusta");
        actions.add(new Runnable() {
            @Override
            public void run() {
                api.save(it.uri, true, toast("Guardado"));
            }
        });
        labels.add("Quitar de tu biblioteca");
        actions.add(new Runnable() {
            @Override
            public void run() {
                api.save(it.uri, false, toast("Quitado"));
            }
        });
        if (it.albumUri != null) {
            labels.add("Ir al álbum");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    open(container(it.albumUri, "album", "Álbum"));
                }
            });
        }
        if (it.artistUri != null) {
            labels.add("Ir al artista");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    open(container(it.artistUri, "artist", "Artista"));
                }
            });
        }
        new AlertDialog.Builder(activity)
                .setTitle(it.title)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
        return true;
    }

    /**
     * Precargar la lista completa: junta todas las páginas (sin mostrarlas de golpe) y le pasa
     * las pistas al servicio, que las baja a la caché con Wi-Fi.
     */
    private void prefetch(final Page p) {
        final List<String> uris = new ArrayList<>();
        for (WebApi.Item it : p.items) {
            if ("track".equals(it.kind)) {
                uris.add(it.uri);
            }
        }
        if (p.next == null) {
            startPrefetch(p.title, uris);
            return;
        }
        Toast.makeText(activity, "Juntando las canciones de la lista…", Toast.LENGTH_SHORT).show();
        collect(p.title, p.next, uris);
    }

    private void collect(final String title, String next, final List<String> uris) {
        api.page(next, new WebApi.Result() {
            @Override
            public void onItems(List<WebApi.Item> items, String more) {
                for (WebApi.Item it : items) {
                    if ("track".equals(it.kind)) {
                        uris.add(it.uri);
                    }
                }
                if (more != null && uris.size() < 2000) {
                    collect(title, more, uris);
                } else {
                    startPrefetch(title, uris);
                }
            }

            @Override
            public void onError(String message) {
                if (uris.isEmpty()) {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                } else {
                    startPrefetch(title, uris); // al menos lo que se pudo juntar
                }
            }
        });
    }

    private void startPrefetch(String title, List<String> uris) {
        if (uris.isEmpty()) {
            Toast.makeText(activity, "No hay canciones para precargar", Toast.LENGTH_SHORT).show();
            return;
        }
        // ~0,7 MB por minuto a 96 kbps; una canción promedio ronda 2,5 MB.
        int mb = (int) (uris.size() * 2.5f * prefs.bitrate() / 96f);
        Toast.makeText(activity, "Precargando " + uris.size() + " canciones (~" + mb
                + " MB). Mirá el progreso en la notificación.", Toast.LENGTH_LONG).show();
        app.tinyl.PlayerService.prefetch(activity, title, uris);
    }

    /** Elegir una de tus playlists y agregarle la canción. */
    private void pickPlaylist(final WebApi.Item track) {
        api.myOwnedPlaylists(EngineState.get().username, new WebApi.Result() {
            @Override
            public void onItems(final List<WebApi.Item> lists, String next) {
                if (lists.isEmpty()) {
                    Toast.makeText(activity, "No tenés playlists propias: creá una con Crear",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] names = new String[lists.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = lists.get(i).title;
                }
                new AlertDialog.Builder(activity)
                        .setTitle("Agregar a una playlist")
                        .setItems(names, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                api.addToPlaylist(lists.get(which).uri, track.uri,
                                        toast("Agregada a " + lists.get(which).title));
                            }
                        })
                        .show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Abrir una playlist (p. ej. recién creada con "Crear"). */
    void openPlaylist(WebApi.Item playlist) {
        open(playlist);
    }

    /** Avisos hacia MainActivity (rellenar el buscador al tocar una búsqueda reciente). */
    interface Listener {
        void onSearchAgain(String query);

        /** Raíz de la pestaña o pantalla interna (playlist, álbum, cola): los filtros van solo en la raíz. */
        void onDepthChanged(boolean atRoot);
    }

    private Listener listener;

    void setListener(Listener l) {
        listener = l;
    }

    /** Abrir el álbum/artista de la canción que está sonando (desde el reproductor). */
    void openNowPlaying(final boolean artist) {
        final EngineState s = EngineState.get();
        if (s.uri.length() == 0) {
            return;
        }
        // El motor no trae los URIs de álbum/artista: se piden a la API (un pedido, a demanda).
        api.trackRefs(s.uri, new WebApi.Refs() {
            @Override
            public void onRefs(String albumUri, String artistUri) {
                String uri = artist ? artistUri : albumUri;
                if (uri != null) {
                    open(container(uri, artist ? "artist" : "album", artist ? s.artist : s.album));
                }
            }
        });
    }

    private static WebApi.Item container(String uri, String kind, String title) {
        return WebApi.Item.make(uri, title == null ? "" : title, "", kind);
    }

    private WebApi.Done toast(final String ok) {
        return new WebApi.Done() {
            @Override
            public void onDone(boolean success, String message) {
                Toast.makeText(activity, success ? ok : message, Toast.LENGTH_SHORT).show();
            }
        };
    }

    /** Radio: esa canción y después similares (autoplay de Spotify), aunque esté apagado. */
    private void startRadio(String trackUri) {
        EngineState.get().contextUri = trackUri;
        NativeEngine.command("radio", "uri", trackUri);
        Toast.makeText(activity, "Radio: después siguen canciones similares", Toast.LENGTH_SHORT).show();
        reloadQueueSoon();
    }

    private static void play(String uri, String offsetUri) {
        if (uri == null) {
            return;
        }
        EngineState.get().contextUri = uri;
        if (offsetUri != null) {
            NativeEngine.command("play", "uri", uri, "offset_uri", offsetUri);
        } else {
            NativeEngine.command("play", "uri", uri);
        }
    }

    private static WebApi.Item nav(String title, String key, String subtitle) {
        return WebApi.Item.make(key, title, subtitle, NAV);
    }

    // ================================================================ render

    private void render() {
        Page p = top();
        title.setText(p.title);
        back.setVisibility(stack.size() > 1 ? View.VISIBLE : View.GONE);
        if (avatar != null) {
            avatar.setVisibility(stack.size() > 1 ? View.GONE : View.VISIBLE);
        }
        if (listener != null) {
            listener.onDepthChanged(stack.size() <= 1);
        }
        List<WebApi.Item> rows = new ArrayList<>();
        boolean hasPlayable = false;
        for (WebApi.Item it : p.items) {
            hasPlayable |= it.isPlayable();
        }
        if (p.contextUri != null && hasPlayable) {
            rows.add(WebApi.Item.make("", "Reproducir", "", PLAY_ALL));
            rows.add(WebApi.Item.make("", "Aleatorio", "", SHUFFLE_ALL));
            if (!p.contextUri.contains(":artist:")) {
                rows.add(WebApi.Item.make("", "Precargar", "Baja el audio ahora con Wi-Fi y después casi no gasta datos",
                        PREFETCH));
            }
        }
        if (p.grid) {
            // Atajos (todo lo que está antes del primer encabezado) en filas de a dos.
            int i = 0;
            while (i < p.items.size() && !p.items.get(i).isHeader()) {
                WebApi.Item pair = WebApi.Item.make("", "", "", PAIR);
                pair.left = p.items.get(i);
                pair.right = i + 1 < p.items.size() && !p.items.get(i + 1).isHeader() ? p.items.get(i + 1) : null;
                rows.add(pair);
                i += pair.right == null ? 1 : 2;
            }
            addWithCards(rows, p.items.subList(i, p.items.size()));
        } else {
            addWithCards(rows, p.items);
        }
        if (p.next != null && !p.loading) {
            rows.add(WebApi.Item.make("", "Cargar más…", "", MORE));
        }
        adapter.set(rows);
        if (p.loading && p.items.isEmpty()) {
            empty.setText("Cargando…");
        } else if (p.error != null) {
            empty.setText("Error: " + p.error);
        } else if (p.items.size() <= p.fixed && p.loader != null && !p.loading) {
            empty.setText(p.emptyText);
        } else {
            empty.setText("");
        }
    }

    /** Las tarjetas (item.card) van de a tres por fila; el resto, una por fila. */
    private static void addWithCards(List<WebApi.Item> rows, List<WebApi.Item> items) {
        for (int i = 0; i < items.size(); i++) {
            WebApi.Item it = items.get(i);
            if (!it.card) {
                rows.add(it);
                continue;
            }
            WebApi.Item pair = WebApi.Item.make("", "", "", CARDS);
            pair.left = it;
            if (i + 1 < items.size() && items.get(i + 1).card) {
                pair.right = items.get(++i);
                if (i + 1 < items.size() && items.get(i + 1).card) {
                    pair.third = items.get(++i);
                }
            }
            rows.add(pair);
        }
    }

    private View avatar;

    /** El avatar del encabezado se oculta cuando aparece "atrás". */
    void setAvatar(View v) {
        avatar = v;
    }

    private final class Adapter extends BaseAdapter {
        final List<WebApi.Item> rows = new ArrayList<>();

        void set(List<WebApi.Item> list) {
            rows.clear();
            rows.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int i) {
            WebApi.Item it = rows.get(i);
            return it.isHeader() ? 1 : PAIR.equals(it.kind) ? 2 : CARDS.equals(it.kind) ? 3 : 0;
        }

        @Override
        public boolean isEnabled(int i) {
            WebApi.Item it = rows.get(i);
            return !it.isHeader() && !PAIR.equals(it.kind) && !CARDS.equals(it.kind); // las celdas manejan su toque
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            WebApi.Item it = rows.get(i);
            if (it.isHeader()) {
                if (v == null) {
                    v = activity.getLayoutInflater().inflate(R.layout.item_header, parent, false);
                }
                ((TextView) v).setText(it.title);
                return v;
            }
            if (CARDS.equals(it.kind)) {
                if (v == null) {
                    v = activity.getLayoutInflater().inflate(R.layout.item_card_pair, parent, false);
                }
                bindCard(v.findViewById(R.id.card_a), it.left);
                bindCard(v.findViewById(R.id.card_b), it.right);
                bindCard(v.findViewById(R.id.card_c), it.third);
                return v;
            }
            if (PAIR.equals(it.kind)) {
                if (v == null) {
                    v = activity.getLayoutInflater().inflate(R.layout.item_pair, parent, false);
                }
                bindCell(v.findViewById(R.id.cell_a), it.left);
                bindCell(v.findViewById(R.id.cell_b), it.right);
                return v;
            }
            if (v == null) {
                v = activity.getLayoutInflater().inflate(R.layout.item_result, parent, false);
            }
            TextView t1 = (TextView) v.findViewById(android.R.id.text1);
            TextView t2 = (TextView) v.findViewById(android.R.id.text2);
            android.widget.ImageView icon = (android.widget.ImageView) v.findViewById(android.R.id.icon);
            t1.setText(it.title);
            boolean special = PLAY_ALL.equals(it.kind) || SHUFFLE_ALL.equals(it.kind) || MORE.equals(it.kind)
                    || PREFETCH.equals(it.kind) || RADIO.equals(it.kind)
                    || (AUTOPLAY.equals(it.kind) && prefs.autoplay())
                    || CLEAR_HISTORY.equals(it.kind);
            boolean current = it.uri.length() > 0 && it.uri.equals(EngineState.get().uri);
            t1.setTextColor(activity.getResources().getColor(
                    special || current ? R.color.accent : R.color.text));
            t1.setTypeface(null, special || NAV.equals(it.kind) || LIKED.equals(it.kind)
                    ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            t2.setText(it.subtitle);
            t2.setVisibility(it.subtitle.length() == 0 ? View.GONE : View.VISIBLE);
            Round.clip(icon, "artist".equals(it.kind) ? -1 : dp(4));
            bindIcon(icon, it);
            return v;
        }

        /** Tarjeta grande: imagen cuadrada (redonda si es artista), nombre y tipo. */
        private void bindCard(View card, final WebApi.Item it) {
            if (it == null) {
                card.setVisibility(View.INVISIBLE);
                return;
            }
            card.setVisibility(View.VISIBLE);
            android.widget.ImageView img = (android.widget.ImageView) card.findViewById(android.R.id.icon);
            int side = (activity.getResources().getDisplayMetrics().widthPixels - dp(48)) / 3;
            if (img.getLayoutParams().height != side) {
                img.getLayoutParams().height = side;
                img.requestLayout();
            }
            Round.clip(img, "artist".equals(it.kind) ? -1 : dp(8));
            ((TextView) card.findViewById(android.R.id.text1)).setText(it.title);
            ((TextView) card.findViewById(android.R.id.text2)).setText(it.subtitle);
            bindIcon(img, it);
            if (img.getPaddingLeft() > 0) { // ícono provisorio sin imagen: más chico dentro de la tarjeta
                int p = side / 3;
                img.setPadding(p, p, p, p);
            }
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Browser.this.onClick(it);
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return Browser.this.onLongClick(it);
                }
            });
        }

        private void bindCell(View cell, final WebApi.Item it) {
            if (it == null) {
                cell.setVisibility(View.INVISIBLE);
                return;
            }
            cell.setVisibility(View.VISIBLE);
            Round.clip(cell, dp(8));
            TextView t = (TextView) cell.findViewById(android.R.id.text1);
            t.setText(it.title);
            boolean current = it.uri.length() > 0 && it.uri.equals(EngineState.get().contextUri);
            t.setTextColor(activity.getResources().getColor(current ? R.color.accent : R.color.text));
            bindIcon((android.widget.ImageView) cell.findViewById(android.R.id.icon), it);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Browser.this.onClick(it);
                }
            });
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return Browser.this.onLongClick(it);
                }
            });
        }

        /** Miniatura de la API, o un ícono si es una fila de navegación/acción. */
        private void bindIcon(android.widget.ImageView icon, WebApi.Item it) {
            int pad = 0;
            int res = 0;
            int bg = R.drawable.thumb_bg;
            int tint = R.color.muted;
            switch (it.kind) {
                case LIKED:
                    pad = dp(13);
                    res = R.drawable.ic_heart;
                    bg = R.drawable.liked_tile;
                    tint = R.color.text;
                    break;
                case QUERY:
                case CLEAR_HISTORY:
                    pad = dp(13);
                    res = CLEAR_HISTORY.equals(it.kind) ? R.drawable.ic_close : R.drawable.ic_history;
                    bg = 0;
                    break;
                case NAV:
                    pad = dp(12);
                    switch (it.uri) {
                        case "liked":
                            res = R.drawable.ic_heart;
                            bg = R.drawable.liked_tile;
                            tint = R.color.text;
                            break;
                        case "playlists":
                            res = R.drawable.ic_library;
                            break;
                        case "albums":
                            res = R.drawable.ic_album;
                            break;
                        case "artists":
                            res = R.drawable.ic_artist;
                            break;
                        default:
                            res = R.drawable.ic_history;
                    }
                    break;
                case PLAY_ALL:
                    pad = dp(13);
                    res = R.drawable.ic_play;
                    bg = R.drawable.play_button;
                    tint = R.color.bg;
                    break;
                case SHUFFLE_ALL:
                    pad = dp(13);
                    res = R.drawable.ic_shuffle;
                    tint = R.color.accent;
                    break;
                case PREFETCH:
                    pad = dp(13);
                    res = R.drawable.ic_download;
                    tint = R.color.accent;
                    break;
                case AUTOPLAY:
                    pad = dp(13);
                    res = R.drawable.ic_repeat;
                    tint = prefs.autoplay() ? R.color.accent : R.color.muted;
                    break;
                case RADIO:
                    pad = dp(13);
                    res = R.drawable.ic_radio;
                    tint = R.color.accent;
                    break;
                case MORE:
                    icon.setVisibility(View.GONE);
                    return;
                default:
            }
            icon.setVisibility(View.VISIBLE);
            if (bg == 0) {
                icon.setBackgroundDrawable(null);
            } else {
                icon.setBackgroundResource(bg);
            }
            icon.setPadding(pad, pad, pad, pad);
            if (res != 0) {
                icon.setTag(null);
                icon.setImageResource(res);
                icon.setColorFilter(activity.getResources().getColor(tint));
                return;
            }
            icon.clearColorFilter();
            if (prefs.eco() || it.image == null) {
                // Sin imagen: nota musical tenue (artista/álbum/canción)
                icon.setTag(null);
                int p2 = dp(13);
                icon.setPadding(p2, p2, p2, p2);
                icon.setImageResource("artist".equals(it.kind) ? R.drawable.ic_artist
                        : "album".equals(it.kind) ? R.drawable.ic_album
                        : "show".equals(it.kind) || "episode".equals(it.kind) ? R.drawable.ic_podcast
                        : R.drawable.ic_note);
                icon.setColorFilter(activity.getResources().getColor(R.color.panel_edge));
                return;
            }
            CoverLoader.into(icon, it.image);
        }

        private int dp(int v) {
            return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    void refreshHighlight() {
        adapter.notifyDataSetChanged();
        if (!stack.isEmpty() && top().queue) {
            reloadQueueSoon(); // cambió la canción: la cola avanzó
        }
    }
}
