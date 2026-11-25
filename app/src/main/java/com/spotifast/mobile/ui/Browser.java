package com.spotifast.mobile.ui;

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

import com.spotifast.mobile.R;
import com.spotifast.mobile.engine.EngineState;
import com.spotifast.mobile.engine.NativeEngine;
import com.spotifast.mobile.web.WebApi;

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

    private final Activity activity;
    private final ListView list;
    private final TextView empty;
    private final TextView title;
    private final View back;
    private final WebApi api;
    private final com.spotifast.mobile.Prefs prefs;
    private final ArrayList<Page> stack = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    Browser(Activity activity, ListView list, TextView empty, TextView title, View back) {
        this.activity = activity;
        this.list = list;
        this.empty = empty;
        this.title = title;
        this.back = back;
        this.api = WebApi.get(activity);
        this.prefs = new com.spotifast.mobile.Prefs(activity);
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

    void showSearch(final String query) {
        root(new Page("Resultados: " + query, null, new Loader() {
            @Override
            public void load(WebApi api, WebApi.Result r) {
                api.search(query, r);
            }
        }));
    }

    void showLibrary() {
        Page p = new Page("Biblioteca", null, null);
        p.items.add(nav("Tus me gusta", "liked", "Canciones guardadas"));
        p.items.add(nav("Playlists", "playlists", "Tuyas y seguidas"));
        p.items.add(nav("Álbumes", "albums", "Guardados"));
        p.items.add(nav("Artistas", "artists", "Que seguís"));
        p.items.add(nav("Escuchado recientemente", "recent", "Últimas 50"));
        root(p);
    }

    void showQueue() {
        root(new Page("Cola", null, new Loader() {
            @Override
            public void load(WebApi api, WebApi.Result r) {
                api.queue(r);
            }
        }));
    }

    /** Pantalla con filas ya armadas (modo demo para capturas). */
    void showStatic(String title, String contextUri, List<WebApi.Item> items, boolean asRoot) {
        Page p = new Page(title, contextUri, null);
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
        if (p.loader != null && p.items.isEmpty()) {
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
        if (p.items.isEmpty() || p.next == null) {
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
                play(uri, null); // podcasts: por ahora se reproducen desde el principio
                break;
            default:
        }
    }

    private void openLibrarySection(String key) {
        switch (key) {
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
            case "header":
                return;
            default:
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
        if (it.isHeader() || NAV.equals(it.kind) || MORE.equals(it.kind)
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

    private static void play(String uri, String offsetUri) {
        if (uri == null) {
            return;
        }
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
        List<WebApi.Item> rows = new ArrayList<>();
        boolean hasPlayable = false;
        for (WebApi.Item it : p.items) {
            hasPlayable |= it.isPlayable();
        }
        if (p.contextUri != null && hasPlayable) {
            rows.add(WebApi.Item.make("", "Reproducir", "", PLAY_ALL));
            rows.add(WebApi.Item.make("", "Aleatorio", "", SHUFFLE_ALL));
        }
        rows.addAll(p.items);
        if (p.next != null && !p.loading) {
            rows.add(WebApi.Item.make("", "Cargar más…", "", MORE));
        }
        adapter.set(rows);
        if (p.loading && p.items.isEmpty()) {
            empty.setText("Cargando…");
        } else if (p.error != null) {
            empty.setText("Error: " + p.error);
        } else if (p.items.isEmpty() && p.loader != null) {
            empty.setText("Nada por acá");
        } else {
            empty.setText("");
        }
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
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i).isHeader() ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int i) {
            return !rows.get(i).isHeader();
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            WebApi.Item it = rows.get(i);
            if (it.isHeader()) {
                if (v == null) {
                    v = activity.getLayoutInflater().inflate(R.layout.item_header, parent, false);
                }
                ((TextView) v).setText(it.title.toUpperCase());
                return v;
            }
            if (v == null) {
                v = activity.getLayoutInflater().inflate(R.layout.item_result, parent, false);
            }
            TextView t1 = (TextView) v.findViewById(android.R.id.text1);
            TextView t2 = (TextView) v.findViewById(android.R.id.text2);
            android.widget.ImageView icon = (android.widget.ImageView) v.findViewById(android.R.id.icon);
            t1.setText(it.title);
            boolean special = PLAY_ALL.equals(it.kind) || SHUFFLE_ALL.equals(it.kind) || MORE.equals(it.kind);
            boolean current = it.uri.length() > 0 && it.uri.equals(EngineState.get().uri);
            t1.setTextColor(activity.getResources().getColor(
                    special || current ? R.color.accent : R.color.text));
            t1.setTypeface(null, special || NAV.equals(it.kind) ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            t2.setText(it.subtitle);
            t2.setVisibility(it.subtitle.length() == 0 ? View.GONE : View.VISIBLE);
            bindIcon(icon, it);
            return v;
        }

        /** Miniatura de la API, o un ícono si es una fila de navegación/acción. */
        private void bindIcon(android.widget.ImageView icon, WebApi.Item it) {
            int pad = 0;
            int res = 0;
            int bg = R.drawable.thumb_bg;
            int tint = R.color.muted;
            switch (it.kind) {
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
                case MORE:
                    icon.setVisibility(View.GONE);
                    return;
                default:
            }
            icon.setVisibility(View.VISIBLE);
            icon.setBackgroundResource(bg);
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
                        : "album".equals(it.kind) ? R.drawable.ic_album : R.drawable.ic_note);
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
    }
}
