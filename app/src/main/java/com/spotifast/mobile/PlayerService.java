package com.spotifast.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.spotifast.mobile.engine.AudioOut;
import com.spotifast.mobile.engine.EngineState;
import com.spotifast.mobile.engine.NativeEngine;
import com.spotifast.mobile.media.MediaControls;
import com.spotifast.mobile.ui.CoverLoader;
import com.spotifast.mobile.ui.MainActivity;

/**
 * Dueño del motor. Mientras suena: servicio en primer plano + wakelock parcial + wifilock
 * (lo mínimo para que Android no corte el audio). En pausa suelta todo y, si la app no está
 * a la vista, apaga el motor después de {@link Prefs#idleMinutes()} minutos.
 */
public final class PlayerService extends Service implements EngineState.Observer {

    public static final String ACTION_START = "com.spotifast.START";
    public static final String EXTRA_TOKEN = "token";
    private static final String ACTION_TOGGLE = "com.spotifast.TOGGLE";
    private static final String ACTION_NEXT = "com.spotifast.NEXT";
    private static final String ACTION_PREV = "com.spotifast.PREV";
    private static final String ACTION_QUIT = "com.spotifast.QUIT";
    private static final String ACTION_PAIR = "com.spotifast.PAIR";
    private static final String ACTION_PAIR_CANCEL = "com.spotifast.PAIR_CANCEL";
    private static final String ACTION_PREFETCH = "com.spotifast.PREFETCH";
    private static final String ACTION_PREFETCH_CANCEL = "com.spotifast.PREFETCH_CANCEL";
    private static final int PREFETCH_NOTIF_ID = 2;
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    /** Lo marca MainActivity: si la UI está visible no se apaga por inactividad. */
    public static volatile boolean uiVisible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioOut audio = new AudioOut();
    private Prefs prefs;
    private MediaControls controls;
    private WifiManager.MulticastLock multicastLock;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean foreground;
    private boolean noisyRegistered;
    private String lastMetaUri;

    private final Runnable idleStop = new Runnable() {
        @Override
        public void run() {
            EngineState s = EngineState.get();
            if (!s.isPlaying() && !uiVisible && !s.pairing && !prefetching) {
                stopSelf();
            } else if (!s.isPlaying()) {
                scheduleIdleStop();
            }
        }
    };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // auriculares desconectados
            NativeEngine.command("pause");
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    switch (change) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            NativeEngine.command("pause");
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            audio.setDuck(true);
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            audio.setDuck(false);
                            break;
                        default:
                    }
                }
            };

    public static void start(Context c, String token) {
        Intent i = new Intent(c, PlayerService.class).setAction(ACTION_START);
        if (token != null) {
            i.putExtra(EXTRA_TOKEN, token);
        }
        c.startService(i);
    }

    /** Login alternativo: el teléfono aparece en las apps de Spotify de la misma Wi-Fi. */
    /**
     * Precargar una lista de pistas (con Wi-Fi). Corre en el servicio para seguir aunque se
     * cierre la app, con progreso y "Cancelar" en una notificación.
     */
    public static void prefetch(Context c, String title, java.util.List<String> trackUris) {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String u : trackUris) {
            a.put(u);
        }
        c.startService(new Intent(c, PlayerService.class).setAction(ACTION_PREFETCH)
                .putExtra("title", title).putExtra("uris", a.toString()));
    }

    public static void pair(Context c, boolean start) {
        c.startService(new Intent(c, PlayerService.class).setAction(start ? ACTION_PAIR : ACTION_PAIR_CANCEL));
    }

    public static void toggle(Context c) {
        c.startService(new Intent(c, PlayerService.class).setAction(ACTION_TOGGLE));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        NativeEngine.setAudioOut(audio);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spotifast:play");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "spotifast:play");
        wifiLock.setReferenceCounted(false);

        multicastLock = wm.createMulticastLock("spotifast:pair");
        multicastLock.setReferenceCounted(false);
        controls = MediaControls.create(this);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_playback),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        EngineState.get().addObserver(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            EngineState s = EngineState.get();
            if (!s.running) {
                s.markStarting();
                NativeEngine.start(getFilesDir().getAbsolutePath(), getCacheDir().getAbsolutePath(),
                        prefs.deviceName(), prefs.deviceId(), intent.getStringExtra(EXTRA_TOKEN),
                        prefs.bitrate(), prefs.volume(), prefs.cacheMb());
            }
            scheduleIdleStop();
        } else if (ACTION_TOGGLE.equals(action)) {
            NativeEngine.command(EngineState.get().isPlaying() ? "pause" : "play");
        } else if (ACTION_NEXT.equals(action)) {
            NativeEngine.command("skip_next");
        } else if (ACTION_PREV.equals(action)) {
            NativeEngine.command("skip_prev");
        } else if (ACTION_QUIT.equals(action)) {
            stopSelf();
        } else if (ACTION_PAIR.equals(action)) {
            // Android filtra multicast en Wi-Fi si nadie tiene este lock: sin él, mDNS no llega.
            multicastLock.acquire();
            EngineState.get().markPairing();
            NativeEngine.startPairing(getFilesDir().getAbsolutePath(), getCacheDir().getAbsolutePath(),
                    prefs.deviceName(), prefs.deviceId(), wifiIp(), prefs.bitrate(), prefs.volume(),
                    prefs.cacheMb());
            scheduleIdleStop();
        } else if (ACTION_PREFETCH.equals(action)) {
            startPrefetch(intent.getStringExtra("title"), intent.getStringExtra("uris"));
        } else if (ACTION_PREFETCH_CANCEL.equals(action)) {
            NativeEngine.prefetchCancel();
        } else if (ACTION_PAIR_CANCEL.equals(action)) {
            NativeEngine.stopPairing();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onStateChanged(EngineState s) {
        if (!s.pairing && multicastLock.isHeld()) {
            multicastLock.release();
        }
        boolean playing = s.isPlaying() || "buffering".equals(s.status);
        if (playing) {
            handler.removeCallbacks(idleStop);
            acquire();
        } else if (!prefetching) {
            release();
            scheduleIdleStop();
        }
        updateSession(s);
        updateNotification(s, playing);
        if (!s.running && !s.loggedIn) {
            dropForeground(true);
        }
    }

    // ------------------------------------------------------------------ precarga

    private String prefetchTitle;
    private boolean prefetching;

    private final NativeEngine.Listener prefetchListener = new NativeEngine.Listener() {
        @Override
        public void onEngineEvent(String type, org.json.JSONObject ev) {
            if (!"prefetch".equals(type)) {
                return;
            }
            String st = ev.optString("state");
            int done = ev.optInt("done"), total = ev.optInt("total");
            long mb = ev.optLong("bytes") / (1024 * 1024);
            if ("progress".equals(st)) {
                prefetchNotification(done + " de " + total + " · " + mb + " MB", done, total, true);
                return;
            }
            prefetching = false;
            releasePrefetchLocks();
            String text;
            switch (st) {
                case "finished":
                    int failed = ev.optInt("failed");
                    text = "Lista: " + (done - failed) + " de " + total + " canciones guardadas (" + mb + " MB)"
                            + (failed > 0 ? " · " + failed + " no disponibles" : "");
                    break;
                case "cancelled":
                    text = "Cancelada: " + done + " de " + total;
                    break;
                case "busy":
                    text = "Ya hay una precarga en curso";
                    break;
                default:
                    text = ev.optString("message", "No se pudo precargar");
            }
            prefetchNotification(text, 0, 0, false);
            if (!uiVisible && !EngineState.get().isPlaying()) {
                scheduleIdleStop();
            }
        }
    };

    private void startPrefetch(String title, String urisJson) {
        prefetchTitle = title == null ? "Playlist" : title;
        if (prefs.prefetchWifiOnly() && !onWifi()) {
            prefetchNotification("Conectate a una red Wi-Fi para precargar (o cambialo en Ajustes)", 0, 0, false);
            return;
        }
        EngineState s = EngineState.get();
        if (!s.running) {
            // sin motor no hay sesión: arrancarlo y reintentar cuando conecte
            s.markStarting();
            NativeEngine.start(getFilesDir().getAbsolutePath(), getCacheDir().getAbsolutePath(),
                    prefs.deviceName(), prefs.deviceId(), null,
                    prefs.bitrate(), prefs.volume(), prefs.cacheMb());
        }
        NativeEngine.addListener(prefetchListener);
        prefetching = true;
        handler.removeCallbacks(idleStop);
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        if (!wifiLock.isHeld()) {
            wifiLock.acquire();
        }
        prefetchNotification("Preparando…", 0, 0, true);
        final String uris = urisJson == null ? "[]" : urisJson;
        // Si el motor recién arranca, esperar a que haya sesión (como máximo ~20 s).
        handler.post(new Runnable() {
            int tries;

            @Override
            public void run() {
                if (EngineState.get().loggedIn || tries++ > 40) {
                    NativeEngine.prefetch(uris, prefs.bitrate(), 1);
                } else {
                    handler.postDelayed(this, 500);
                }
            }
        });
    }

    private void releasePrefetchLocks() {
        if (!EngineState.get().isPlaying()) {
            release();
        }
    }

    @SuppressWarnings("deprecation")
    private boolean onWifi() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected() && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
    }

    @SuppressWarnings("deprecation")
    private void prefetchNotification(String text, int done, int total, boolean ongoing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle("Precarga · " + prefetchTitle)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), pendingFlags()));
        if (ongoing) {
            b.setProgress(Math.max(total, 1), done, total == 0);
            b.addAction(R.drawable.ic_close, "Cancelar", service(ACTION_PREFETCH_CANCEL, 5));
        }
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(PREFETCH_NOTIF_ID, b.build());
    }

    private void scheduleIdleStop() {
        handler.removeCallbacks(idleStop);
        handler.postDelayed(idleStop, prefs.idleMinutes() * 60_000L);
    }

    @SuppressWarnings("deprecation")
    private void acquire() {
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        if (!wifiLock.isHeld()) {
            wifiLock.acquire();
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(focusListener).build();
            }
            am.requestAudioFocus(focusRequest);
        } else {
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        if (!noisyRegistered) {
            registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
    }

    private void release() {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (noisyRegistered) {
            unregisterReceiver(noisy);
            noisyRegistered = false;
        }
    }

    private void updateSession(EngineState s) {
        boolean changed = !s.uri.equals(lastMetaUri);
        lastMetaUri = s.uri;
        Bitmap art = prefs.eco() ? null : CoverLoader.cached(s.coverDefault);
        controls.update(s, art, changed);
    }

    @SuppressWarnings("deprecation")
    private String wifiIp() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int ip = wm.getConnectionInfo() == null ? 0 : wm.getConnectionInfo().getIpAddress();
        if (ip == 0) {
            return "";
        }
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    @SuppressWarnings("deprecation")
    private void updateNotification(EngineState s, boolean playing) {
        if (s.title.length() == 0 && !playing) {
            return;
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle(s.title.length() == 0 ? getString(R.string.app_name) : s.title)
                .setContentText(s.artist)
                .setShowWhen(false)
                .setOngoing(playing)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), pendingFlags()))
                .setDeleteIntent(service(ACTION_QUIT, 4))
                .addAction(R.drawable.ic_prev, getString(R.string.prev), service(ACTION_PREV, 1))
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        getString(playing ? R.string.pause : R.string.play), service(ACTION_TOGGLE, 2))
                .addAction(R.drawable.ic_next, getString(R.string.next), service(ACTION_NEXT, 3));
        controls.decorate(b);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        Notification n = b.build();
        if (playing && !foreground) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                foreground = true;
            } catch (RuntimeException e) {
                // Android 12+ puede negarlo si arrancó desde segundo plano (p. ej. desde otro
                // dispositivo Connect). Se sigue mostrando como notificación normal.
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
            }
        } else {
            if (!playing && foreground) {
                dropForeground(false);
            }
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void dropForeground(boolean removeNotification) {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(removeNotification ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(removeNotification);
        }
        foreground = false;
    }

    private PendingIntent service(String action, int req) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, req, i, pendingFlags());
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @Override
    public void onDestroy() {
        NativeEngine.removeListener(prefetchListener);
        if (prefetching) {
            NativeEngine.prefetchCancel();
        }
        EngineState.get().removeObserver(this);
        handler.removeCallbacks(idleStop);
        NativeEngine.stop();
        release();
        dropForeground(true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        NativeEngine.stopPairing();
        if (multicastLock.isHeld()) {
            multicastLock.release();
        }
        controls.release();
        NativeEngine.setAudioOut(null);
        audio.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
