package com.wygtv.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Keeps a stream going when the connection hiccups. Without this the player stops on the first network
 * error (the picture freezes on the last frame and never comes back). It:
 *  - reconnects after an error, waiting 1, 2, 4 then 8 seconds between tries, and gives up after 8 tries in a row;
 *  - jumps back to the live edge when a live stream falls behind (HLS "behind live window");
 *  - watches for silent stalls: buffering for 20 seconds, or a live picture that hasn't moved for 20 seconds,
 *    and reconnects then too;
 *  - switches to the FFmpeg audio decoder and tries again at once when the device's own audio decoder fails.
 * The try count resets once the stream has played smoothly for 30 seconds, or when the channel changes.
 */
final class Recovery implements Player.Listener {
    interface GiveUp { void run(PlaybackException error); }

    static final int MAX_TRIES = 8;
    static final long STALL_MS = 20_000, HEALTHY_MS = 30_000, CHECK_MS = 5_000;

    private final Context c;
    private final ExoPlayer p;
    private final boolean live;
    private final GiveUp giveUp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable retry = () -> { retryPending = false; reconnect(); }, watchdog = this::check;
    private int tries;
    private boolean everPlayed, stopped, toldUser, retryPending;
    private long bufferingSince = -1, playingSince = -1, lastPos = -1, posSince;

    private Recovery(Context c, ExoPlayer p, boolean live, GiveUp giveUp) {
        this.c = c;
        this.p = p;
        this.live = live;
        this.giveUp = giveUp;
        p.addListener(this);
        ui.postDelayed(watchdog, CHECK_MS);
    }

    static Recovery attach(Context c, ExoPlayer p, boolean live, GiveUp giveUp) { return new Recovery(c, p, live, giveUp); }

    /** Wait before try number {@code attempt} (0 = first): 1 s, 2 s, 4 s, then 8 s. */
    static long delayMs(int attempt) { return 1000L << Math.max(0, Math.min(attempt, 3)); }

    /** Errors worth another try: network and stream-reading problems, a live stream that fell behind,
     *  or anything at all once the stream had already played (a mid-stream glitch). */
    static boolean retryable(int errorCode, boolean everPlayed) {
        if (everPlayed) return true;
        return errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                || (errorCode >= 2000 && errorCode < 4000) || errorCode == PlaybackException.ERROR_CODE_TIMEOUT;
    }

    /** A server that says no (wrong login, no such channel, all tuners busy) won't change its mind in a few seconds. */
    static boolean refused(int httpCode) { return httpCode == 401 || httpCode == 403 || httpCode == 404 || httpCode == 503; }

    /** A different channel or video starts: forget earlier trouble. */
    void newStream() {
        ui.removeCallbacks(retry);
        retryPending = false;
        tries = 0;
        everPlayed = false;
        toldUser = false;
        bufferingSince = -1;
        playingSince = -1;
        lastPos = -1;
    }

    void stop() {
        stopped = true;
        ui.removeCallbacksAndMessages(null);
        p.removeListener(this);
    }

    @Override public void onPlaybackStateChanged(int state) {
        bufferingSince = state == Player.STATE_BUFFERING ? SystemClock.elapsedRealtime() : -1;
    }

    @Override public void onIsPlayingChanged(boolean playing) {
        if (playing) everPlayed = true;
        playingSince = playing ? SystemClock.elapsedRealtime() : -1;
        lastPos = -1;
    }

    @Override public void onPlayerError(PlaybackException error) {
        if (stopped) return;
        ui.removeCallbacks(retry);
        retryPending = false;
        if (!everPlayed && refused(httpCode(error))) { giveUp.run(error); return; }
        if (Players.useFfmpegAudio(p, error)) {
            Toast.makeText(c, "This device's audio decoder failed. Trying the built-in (FFmpeg) one…", Toast.LENGTH_SHORT).show();
            reconnect();
            return;
        }
        if (!retryable(error.errorCode, everPlayed) || tries >= MAX_TRIES) { giveUp.run(error); return; }
        say();
        retryPending = true;
        ui.postDelayed(retry, delayMs(tries++));
    }

    private void check() {
        if (stopped) return;
        ui.postDelayed(watchdog, CHECK_MS);
        long now = SystemClock.elapsedRealtime();
        if (!p.getPlayWhenReady() || retryPending) { lastPos = -1; return; }
        int state = p.getPlaybackState();
        if (state == Player.STATE_BUFFERING && bufferingSince > 0 && now - bufferingSince > STALL_MS) {
            stalled();
            return;
        }
        if (live && p.isPlaying()) {
            long pos = p.getCurrentPosition();
            if (pos != lastPos) { lastPos = pos; posSince = now; }
            else if (now - posSince > STALL_MS) { stalled(); return; }
        }
        if (playingSince > 0 && now - playingSince > HEALTHY_MS) { tries = 0; toldUser = false; }
    }

    private void stalled() {
        if (tries >= MAX_TRIES) return;
        tries++;
        say();
        reconnect();
    }

    /** Opens the stream again: live from the newest point, on-demand from where it was. Doesn't un-pause. */
    private void reconnect() {
        if (stopped) return;
        MediaItem item = p.getCurrentMediaItem();
        if (item == null) return;
        bufferingSince = -1;
        lastPos = -1;
        if (live) p.setMediaItem(item);
        else p.setMediaItem(item, Math.max(0, p.getCurrentPosition()));
        p.prepare();
    }

    private void say() {
        if (toldUser) return;
        toldUser = true;
        Toast.makeText(c, "Connection dropped. Reconnecting…", Toast.LENGTH_SHORT).show();
    }

    static int httpCode(Throwable t) {
        while (t != null && !(t instanceof HttpDataSource.InvalidResponseCodeException)) t = t.getCause();
        return t == null ? 0 : ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
    }
}
