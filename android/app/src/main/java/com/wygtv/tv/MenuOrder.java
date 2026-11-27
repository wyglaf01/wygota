package com.wygtv.tv;

import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The order of the side menu, set in Settings → Appearance → Side menu order. Saved in pref {@value #PREF} as a
 * comma-separated list of item keys. Items missing from the saved list (new ones) go where they sit by default.
 */
final class MenuOrder {
    private MenuOrder() { }

    static final String PREF = "menu_order";
    /**
     * Every side-menu item that can be moved. "antenna" stands for all HDHomeRun tuners (and AirTV) together. Favorites has no
     * entry of its own (it is the first item inside Live TV); an old saved "fav" is dropped by {@link #parse}.
     */
    static final String[] DEFAULT = {"home", "guide", "live", "vod", "series", "antenna", "msearch", "playlists", "settings"};
    static final String[] LABELS = {"⌂  Home", "▦  TV Guide", "▣  Live TV", "▶  Movies", "▤  Series", "📡  Antenna TV", "⌕  Search", "☰  Playlists", "⚙  Settings"};

    /** Settings → Appearance → "Exit button on Home". The side menu's Exit is always there. */
    static final String PREF_HOME_EXIT = "home_exit";

    static boolean homeExit(SharedPreferences p) { return p.getBoolean(PREF_HOME_EXIT, true); }

    static List<String> get(SharedPreferences p) { return parse(p.getString(PREF, "")); }

    /** The saved order, cleaned up: unknown keys dropped, duplicates removed, missing keys put back at their default place. */
    static List<String> parse(String saved) {
        List<String> out = new ArrayList<>();
        if (saved != null) for (String k : saved.split(",")) {
            k = k.trim();
            if (indexOf(k) >= 0 && !out.contains(k)) out.add(k);
        }
        for (int i = 0; i < DEFAULT.length; i++) {
            String k = DEFAULT[i];
            if (out.contains(k)) continue;
            // After the default item just before it, or first if none of those are in the list.
            int at = 0;
            for (int j = i - 1; j >= 0; j--) { int pos = out.indexOf(DEFAULT[j]); if (pos >= 0) { at = pos + 1; break; } }
            out.add(at, k);
        }
        return out;
    }

    static String join(List<String> order) { return String.join(",", order); }

    /** Moves the item at {@code from} one place up (step -1) or down (step 1). Returns its new index. */
    static int move(List<String> order, int from, int step) {
        int to = from + step;
        if (from < 0 || from >= order.size() || to < 0 || to >= order.size()) return from;
        String k = order.remove(from);
        order.add(to, k);
        return to;
    }

    /**
     * Where the gap in the side menu goes: before the run of Playlists/Settings at the end of the order, so they keep
     * sitting at the bottom by default. Returns order.size() when the last item is neither.
     */
    static int gapBefore(List<String> keys) {
        int i = keys.size();
        while (i > 0 && (keys.get(i - 1).equals("playlists") || keys.get(i - 1).equals("settings"))) i--;
        return i;
    }

    static int indexOf(String key) { return Arrays.asList(DEFAULT).indexOf(key); }

    static String label(String key) { int i = indexOf(key); return i < 0 ? key : LABELS[i]; }

    /** Settings row action: a pop-up listing the menu. OK picks an item up, ▲ ▼ move it, OK puts it down. */
    static void edit(MainActivity a, Runnable done) {
        List<String> order = get(a.prefs);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
        TextView hint = a.label("OK picks an item up, ▲ ▼ move it, OK puts it down.", 15);
        hint.setTextColor(Theme.MUTED);
        hint.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(8));
        box.addView(hint);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        int[] held = {-1};
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < order.size(); i++) {
                final int at = i;
                boolean holding = held[0] == i;
                TextView row = a.button((holding ? "⇕  " : (i + 1) + ".  ") + label(order.get(i)) + (holding ? "   ▲ ▼ to move, OK to drop" : ""), list, () -> {
                    held[0] = held[0] == at ? -1 : at;
                    draw[0].run();
                    focus(list, at);
                });
                row.setTextSize(18);
                if (holding) row.setTextColor(Theme.ACCENT);
                row.setOnKeyListener((v, code, e) -> {
                    if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                    if (held[0] != at) return false; // not holding: normal focus movement
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        int to = move(order, at, code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                        held[0] = to;
                        draw[0].run();
                        focus(list, to);
                    }
                    return true;
                });
            }
        };
        draw[0].run();
        new Dialogs.Builder(a).setTitle("Side menu order").setView(box)
                .setPositiveButton("Save", (d, w) -> { a.prefs.edit().putString(PREF, join(order)).apply(); done.run(); })
                .setNeutralButton("Reset", (d, w) -> { a.prefs.edit().remove(PREF).apply(); done.run(); })
                .setNegativeButton("Cancel", null).show();
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private static void focus(LinearLayout list, int i) {
        if (i >= 0 && i < list.getChildCount()) list.getChildAt(i).requestFocus();
    }
}
