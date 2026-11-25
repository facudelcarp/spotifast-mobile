package app.tinyl.engine;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Salida PCM 16 bits, 44.1 kHz, estéreo sobre AudioTrack (disponible en todo Android).
 *
 * Buffer grande a propósito (~0.5 s): el hilo de decodificación se despierta menos veces
 * por segundo, que es lo que más pesa en la batería de un reproductor en streaming.
 */
public final class AudioOut {

    private static final int RATE = 44100;
    private AudioTrack track;
    private float volume = 1f;

    @SuppressWarnings("deprecation")
    private synchronized AudioTrack track() {
        if (track == null) {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min * 2, RATE * 2 * 2 / 2);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
            applyVolume();
        }
        return track;
    }

    public void start() {
        AudioTrack t = track();
        if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            t.play();
        }
    }

    public void write(short[] data, int length) {
        AudioTrack t = track();
        if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            t.play();
        }
        int off = 0;
        while (off < length) {
            int n = t.write(data, off, length - off); // bloqueante: contrapresión
            if (n <= 0) {
                break;
            }
            off += n;
        }
    }

    public synchronized void stop() {
        if (track != null) {
            track.pause();
            track.flush();
        }
    }

    private float duck = 1f, fade = 1f;

    /** Para "ducking" cuando otra app pide foco de audio transitorio. */
    public synchronized void setDuck(boolean on) {
        duck = on ? 0.25f : 1f;
        volume = duck * fade;
        applyVolume();
    }

    /** Fundido del temporizador para dormir (1 = normal, 0 = silencio). */
    public synchronized void setFade(float f) {
        fade = Math.max(0f, Math.min(1f, f));
        volume = duck * fade;
        applyVolume();
    }

    @SuppressWarnings("deprecation")
    private void applyVolume() {
        if (track != null) {
            track.setStereoVolume(volume, volume);
        }
    }

    public synchronized void release() {
        if (track != null) {
            track.release();
            track = null;
        }
    }
}
