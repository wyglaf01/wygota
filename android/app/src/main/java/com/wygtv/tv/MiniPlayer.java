package com.wygtv.tv;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;

/**
 * The in-app mini player: the built-in player shrunk to a window in a corner, still playing, while the rest of the
 * app (TV guide, Live TV, Movies, Search, Settings…) is used around it.
 *
 * The same player keeps running: shrinking moves it out of the full-screen player into this window, and OK on the
 * window (or the Window key) moves it back, so nothing re-buffers and an antenna tuner stays tuned. Every screen the
 * app draws gets the window laid on top (see MainActivity.setContentView). Live previews in the guide and Live TV
 * stay off while it plays, so they don't take a second tuner or play sound over it.
 *
 * Keys anywhere while it shows: Play/Pause, Stop (closes it), Window (full screen). Focus the window and press OK for
 * full screen, or hold OK (or Menu) for Move, Picture-in-picture and Close.
 */
@OptIn(markerClass = UnstableApi.class)
final class MiniPlayer {
    /** Which corner: 0 bottom-right, 1 bottom-left, 2 top-right, 3 top-left. Saved, so it stays where it was put. */
    static final String PREF_CORNER = "mini_corner";
    private static final int[] GRAVITY = {Gravity.BOTTOM | Gravity.END, Gravity.BOTTOM | Gravity.START, Gravity.TOP | Gravity.END, Gravity.TOP | Gravity.START};

    private static ExoPlayer player;
    private static Recovery recovery;
    private static Subtitles subs;
    private static String[] watching;
    private static Runnable back;
    private static String type, id, ext, title;
    private static JSONArray zap;
    private static PlayerView view;
    private static LinearLayout window;
    private static TextView label;

    private MiniPlayer() { }

    static boolean active() { return player != null; }

    /** Full-screen player → corner window. The screen the channel or movie was opened from comes back under it. */
    static void shrink(MainActivity a) {
        if (a.player == null || active()) return;
        a.saveWatch();
        player = a.player; recovery = a.recovery; subs = a.subs; watching = a.watching; back = a.playerReturn;
        type = a.remote.currentType(); id = a.remote.currentId(); ext = a.remote.currentExt(); title = a.remote.currentTitle();
        zap = a.remote.currentList();
        PlayerView full = a.remote.currentView();
        a.remote.onPlayerExit();
        a.player = null; a.recovery = null; a.subs = null; a.watching = null;
        buildWindow(a);
        if (full != null) PlayerView.switchTargetView(player, full, view); else view.setPlayer(player);
        player.play();
        a.mode = "mini";
        if (back != null) back.run(); else a.home();
    }

    /** Corner window → full screen, same player (no re-buffering). */
    static void expand(MainActivity a) {
        if (!active()) return;
        LivePreview.release();
        if (a.tvGuide != null) a.tvGuide.pausePreview();
        ExoPlayer p = player;
        PlayerView small = view;
        a.player = p; a.recovery = recovery; a.subs = subs; a.watching = watching; a.playerReturn = back;
        String t = type, i = id, e = ext, n = title;
        JSONArray list = zap;
        detach();
        clear(); // before drawing, so the full-screen player doesn't get the window laid over it
        PlayerView full = new PlayerView(a);
        full.setUseController(true);
        PlayerView.switchTargetView(p, small, full);
        a.screenGeneration++;
        a.mode = "player";
        a.setContentView(a.remote.playerScreen(full, t, i, e, n, list));
        full.requestFocus();
    }

    /** Stops and closes the window, saving how far a movie or episode got. */
    static void close(MainActivity a) {
        if (!active()) return;
        if (watching != null && a.player == null) {
            a.player = player; a.watching = watching;
            a.saveWatch();
            a.player = null; a.watching = null;
        }
        if (recovery != null) recovery.stop();
        ExoPlayer p = player;
        detach();
        clear();
        if (view != null) view.setPlayer(null);
        p.release();
        view = null; window = null; label = null;
        if (a.mode.equals("live")) LivePreview.resume(a);
        if (a.tvGuide != null && a.mode.equals("guide")) a.tvGuide.resumePreview();
    }

    /** The app is going to the background (not into picture-in-picture): pause, like the full-screen player. */
    static void onStop(MainActivity a) { if (active()) { if (watching != null) { a.player = player; a.watching = watching; a.saveWatch(); a.player = null; a.watching = null; } player.pause(); refreshLabel(); } }

    static void onDestroy() { if (active()) { if (recovery != null) recovery.stop(); player.release(); clear(); } }

    private static void clear() { player = null; recovery = null; subs = null; watching = null; back = null; zap = null; }

    private static void detach() {
        if (window != null && window.getParent() instanceof ViewGroup) ((ViewGroup) window.getParent()).removeView(window);
    }

    // ------------------------------------------------------------------ the window

    private static void buildWindow(MainActivity a) {
        view = new PlayerView(a);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShutterBackgroundColor(0xff000000);
        view.setFocusable(false);
        view.setKeepScreenOn(true);
        label = new TextView(a);
        label.setTextSize(13);
        label.setTextColor(0xffffffff);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(4));
        window = new LinearLayout(a);
        window.setOrientation(LinearLayout.VERTICAL);
        window.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3));
        window.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        window.addView(label, new LinearLayout.LayoutParams(-1, -2));
        window.setFocusable(true);
        window.setClickable(true);
        window.setBackground(frame(a, false));
        window.setOnFocusChangeListener((v, has) -> { window.setBackground(frame(a, has)); refreshLabel(); });
        window.setOnClickListener(v -> expand(a));
        window.setOnLongClickListener(v -> { options(a); return true; });
        window.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_DOWN) { options(a); return true; }
            return false;
        });
        refreshLabel();
    }

    private static GradientDrawable frame(MainActivity a, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xee000000);
        g.setCornerRadius(a.dp(10));
        g.setStroke(a.dp(focused ? 3 : 1), focused ? Theme.ACCENT : 0x66ffffff);
        return g;
    }

    private static void refreshLabel() {
        if (label == null) return;
        boolean focused = window != null && window.isFocused();
        String state = player != null && !player.getPlayWhenReady() ? "❚❚  " : "▶  ";
        label.setText(state + (title == null ? "" : title) + (focused ? "   •   OK: full screen   •   hold OK: options" : ""));
    }

    /** Lays the window over a screen the app is about to show. Called for every screen while the mini player is on. */
    static View wrap(MainActivity a, View screen) {
        if (!active() || window == null) return screen;
        detach();
        FrameLayout holder = new FrameLayout(a);
        holder.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        int w = a.dp(352), h = w * 9 / 16 + a.dp(26);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, GRAVITY[corner(a)]);
        lp.setMargins(a.dp(28), a.dp(24), a.dp(28), a.dp(24));
        holder.addView(window, lp);
        refreshLabel();
        return holder;
    }

    private static int corner(MainActivity a) { int c = a.prefs.getInt(PREF_CORNER, 0); return c < 0 || c > 3 ? 0 : c; }

    private static void move(MainActivity a) {
        a.prefs.edit().putInt(PREF_CORNER, (corner(a) + 1) % 4).apply();
        if (window != null && window.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) window.getLayoutParams();
            lp.gravity = GRAVITY[corner(a)];
            window.setLayoutParams(lp);
        }
    }

    private static void options(MainActivity a) {
        if (!active()) return;
        boolean playing = player.getPlayWhenReady();
        java.util.List<String> items = new java.util.ArrayList<>(java.util.Arrays.asList("Full screen", playing ? "Pause" : "Play", "Move to the next corner"));
        if (Pip.supported(a)) items.add("Picture-in-picture (keeps playing outside " + Brand.NAME + ")");
        items.add("Close the mini player");
        new Dialogs.Builder(a).setTitle(title == null ? "Mini player" : title).setItems(items.toArray(new String[0]), (d, w) -> {
            String pick = items.get(w);
            if (pick.equals("Full screen")) expand(a);
            else if (pick.equals("Pause") || pick.equals("Play")) { if (playing) player.pause(); else player.play(); refreshLabel(); }
            else if (pick.startsWith("Move")) move(a);
            else if (pick.startsWith("Picture")) Pip.enter(a);
            else close(a);
        }).show();
    }

    /** Remote keys that work on any screen while the window shows. Returns true when used. */
    static boolean key(MainActivity a, KeyEvent e) {
        if (!active() || a.mode.equals("player")) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (down) { if (player.getPlayWhenReady()) player.pause(); else player.play(); refreshLabel(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) { player.play(); refreshLabel(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) { player.pause(); refreshLabel(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (down) close(a);
                return true;
            case KeyEvent.KEYCODE_WINDOW:
                if (down) expand(a);
                return true;
            default:
                return false;
        }
    }
}
