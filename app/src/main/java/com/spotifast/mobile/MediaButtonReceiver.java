package com.spotifast.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import com.spotifast.mobile.engine.NativeEngine;

/** Botones de auriculares/Bluetooth en Android 4.4 (en 5+ los maneja MediaSession). */
public final class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        KeyEvent e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (e == null || e.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                PlayerService.toggle(c);
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                NativeEngine.command("play");
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                NativeEngine.command("pause");
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                NativeEngine.command("skip_next");
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                NativeEngine.command("skip_prev");
                break;
            default:
        }
    }
}
