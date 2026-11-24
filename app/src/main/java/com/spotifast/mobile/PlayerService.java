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
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.spotifast.mobile.engine.AudioOut;
import com.spotifast.mobile.engine.EngineState;
import com.spotifast.mobile.engine.NativeEngine;
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
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    /** Lo marca MainActivity: si la UI está visible no se apaga por inactividad. */
    public static volatile boolean uiVisible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioOut audio = new AudioOut();
    private Prefs prefs;
    private MediaSession session;
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
            if (!s.isPlaying() && !uiVisible) {
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

        session = new MediaSession(this, "Spotifast");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                NativeEngine.command("play");
            }

            @Override
            public void onPause() {
                NativeEngine.command("pause");
            }

            @Override
            public void onSkipToNext() {
                NativeEngine.command("skip_next");
            }

            @Override
            public void onSkipToPrevious() {
                NativeEngine.command("skip_prev");
            }

            @Override
            public void onSeekTo(long pos) {
                NativeEngine.command("seek", "position_ms", pos);
            }

            @Override
            public void onStop() {
                NativeEngine.command("pause");
            }
        });
        session.setSessionActivity(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), pendingFlags()));
        session.setActive(true);

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
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onStateChanged(EngineState s) {
        boolean playing = s.isPlaying() || "buffering".equals(s.status);
        if (playing) {
            handler.removeCallbacks(idleStop);
            acquire();
        } else {
            release();
            scheduleIdleStop();
        }
        updateSession(s);
        updateNotification(s, playing);
        if (!s.running && !s.loggedIn) {
            dropForeground(true);
        }
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
        int st;
        switch (s.status) {
            case "playing":
                st = PlaybackState.STATE_PLAYING;
                break;
            case "paused":
                st = PlaybackState.STATE_PAUSED;
                break;
            case "buffering":
                st = PlaybackState.STATE_BUFFERING;
                break;
            default:
                st = PlaybackState.STATE_STOPPED;
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(st, s.positionNow(), s.isPlaying() ? 1f : 0f)
                .build());
        if (!s.uri.equals(lastMetaUri)) {
            lastMetaUri = s.uri;
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs);
            Bitmap art = prefs.eco() ? null : CoverLoader.cached(s.coverDefault);
            if (art != null) {
                m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            }
            session.setMetadata(m.build());
        }
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
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), pendingFlags()))
                .setDeleteIntent(service(ACTION_QUIT, 4))
                .addAction(R.drawable.ic_prev, getString(R.string.prev), service(ACTION_PREV, 1))
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        getString(playing ? R.string.pause : R.string.play), service(ACTION_TOGGLE, 2))
                .addAction(R.drawable.ic_next, getString(R.string.next), service(ACTION_NEXT, 3))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
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
        EngineState.get().removeObserver(this);
        handler.removeCallbacks(idleStop);
        NativeEngine.stop();
        release();
        dropForeground(true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        session.release();
        NativeEngine.setAudioOut(null);
        audio.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
