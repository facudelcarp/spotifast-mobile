package com.spotifast.mobile.media;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;

import com.spotifast.mobile.engine.EngineState;
import com.spotifast.mobile.engine.NativeEngine;
import com.spotifast.mobile.ui.MainActivity;

/** Android 5.0+: MediaSession + Notification.MediaStyle. */
@TargetApi(21)
final class SessionControls extends MediaControls {

    private final MediaSession session;

    SessionControls(Context c) {
        session = new MediaSession(c, "Spotifast");
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
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        session.setSessionActivity(PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags));
        session.setActive(true);
    }

    @Override
    public void update(EngineState s, Bitmap art, boolean metadataChanged) {
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
        if (metadataChanged) {
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs);
            if (art != null) {
                m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            }
            session.setMetadata(m.build());
        }
    }

    @Override
    public void decorate(Notification.Builder b) {
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        b.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
    }

    @Override
    public void release() {
        session.release();
    }
}
