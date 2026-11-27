package com.wygtv.tv;

import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * A small notice while the app switches to another playlist's channels, movies or series behind the scenes (picking
 * a category from a playlist whose list isn't loaded yet): "Loading <playlist>…". It stays up for a few seconds,
 * which covers the usual switch; the list itself shows "Loading…" until it's ready.
 */
final class Switching {
    private Switching() { }

    private static Toast showing;

    static void show(MainActivity a, JSONObject playlist) {
        if (showing != null) showing.cancel();
        TextView t = a.label("⟳  Loading " + Library.nameOf(playlist) + "…", 16);
        t.setTextColor(Theme.TEXT);
        t.setBackground(Skin.tile(a, true));
        t.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10));
        Toast toast = new Toast(a.getApplicationContext());
        toast.setView(t);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setGravity(Gravity.TOP | Gravity.END, a.dp(24), a.dp(24));
        toast.show();
        showing = toast;
    }

    /** Takes the notice away early (e.g. when the list has loaded). */
    static void done() {
        if (showing != null) { showing.cancel(); showing = null; }
    }
}
