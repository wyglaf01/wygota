package com.wygtv.tv;

import android.annotation.TargetApi;
import android.app.PictureInPictureParams;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Rational;

import androidx.media3.common.VideoSize;

/**
 * Android picture-in-picture: the built-in player keeps playing in a small system window, in a corner of the TV or
 * tablet, while Home or another app is used. Android 8 and newer, on devices that offer it (most Android TV and
 * Google TV devices, phones and tablets; some Fire TV and older TV boxes don't).
 *
 * It starts from the player's menu, from the mini player's options, or by itself when Home is pressed while
 * something plays (Settings > Player > Picture-in-picture). Pressing the window brings the app back full screen
 * on the player; closing the window stops playback and returns to the screen the channel was opened from.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class Pip {
    /** true (default): pressing Home while the built-in player plays keeps it playing in a picture-in-picture window. */
    static final String PREF_ON_HOME = "pip_on_home";
    /** Set while the app is in picture-in-picture. */
    static boolean inPip;

    /** Picture-in-picture is turned off for now to avoid issues seen in other WygTV editions; set true to bring it back. */
    private static final boolean ENABLED = false;

    private Pip() { }

    static boolean supported(MainActivity a) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    static boolean onHome(SharedPreferences p) { return p.getBoolean(PREF_ON_HOME, true); }

    /** Shrinks the playing channel or movie (full screen, or the mini player) into a picture-in-picture window. */
    static boolean enter(MainActivity a) {
        if (!ENABLED || !supported(a)) return false;
        if (MiniPlayer.active()) MiniPlayer.expand(a);
        if (a.player == null || !a.mode.equals("player")) return false;
        try { return enter26(a); } catch (Exception e) { return false; }
    }

    @TargetApi(26)
    private static boolean enter26(MainActivity a) {
        return a.enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(ratio(a)).build());
    }

    /** The video's own shape, kept inside what Android allows for a picture-in-picture window (2.39:1 to 1:2.39). */
    private static Rational ratio(MainActivity a) {
        VideoSize v = a.player == null ? VideoSize.UNKNOWN : a.player.getVideoSize();
        int w = v.width, h = v.height;
        if (w <= 0 || h <= 0) return new Rational(16, 9);
        w = Math.round(w * v.pixelWidthHeightRatio);
        double r = (double) w / h;
        if (r > 2.39) return new Rational(239, 100);
        if (r < 1 / 2.39) return new Rational(100, 239);
        return new Rational(w, h);
    }

    /** Home pressed (the app is about to go to the background). */
    static void onUserLeaveHint(MainActivity a) {
        if (!ENABLED || !onHome(a.prefs) || !supported(a)) return;
        boolean playing = (a.player != null && a.mode.equals("player") && a.player.getPlayWhenReady()) || MiniPlayer.active();
        if (playing) enter(a);
    }

    /** Into or out of the window: hide the banner and controls while small, show them again when full screen. */
    static void changed(MainActivity a, boolean in) {
        inPip = in;
        a.remote.pictureInPicture(in);
    }

    /**
     * The app stopped. When that happens in picture-in-picture, the window was closed: stop the player and go back
     * to the screen it was opened from, so reopening the app doesn't land on a paused channel. Returns true when done.
     */
    static boolean onStop(MainActivity a) {
        if (!inPip) return false;
        inPip = false;
        Runnable back = a.playerReturn;
        a.closePlayer();
        if (back != null) back.run(); else a.home();
        return true;
    }
}
