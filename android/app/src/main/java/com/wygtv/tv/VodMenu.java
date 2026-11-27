package com.wygtv.tv;

import android.graphics.Typeface;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Movies / Series category list on the left, grouped by playlist. Each saved playlist is a heading that
 * opens and closes (OK on it) to show or hide its categories. The playing playlist starts open. Picking a category
 * of another playlist switches to that playlist first.
 */
final class VodMenu {
    /** Playlists whose headings are open; kept while the app runs. Null until the first screen opens the active one. */
    private static Set<String> open;
    /** Other playlists' categories already fetched, by playlist id + section. */
    private static final Map<String, JSONArray> CACHE = new HashMap<>();
    /** Playlists whose categories are being fetched right now. */
    private static final Set<String> LOADING = new HashSet<>();

    private VodMenu() { }

    /** Builds the grouped list under the fixed buttons (Home, Search, My List). */
    static void render(MainActivity a) { render(a, null); }

    private static void render(MainActivity a, String focusPlaylist) {
        String active = a.library.activeId();
        if (open == null) { open = new HashSet<>(); open.add(active); }
        a.side.removeViews(a.sideFixed, Math.max(0, a.side.getChildCount() - a.sideFixed));
        JSONArray pls = a.library.inUse();
        TextView focusMe = null;
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null || Library.isHdhr(p)) continue;
            String pid = p.optString("id");
            boolean isActive = pid.equals(active);
            // M3U playlists have no movies or series; show one only while it is the playing playlist.
            if (!isActive && Library.M3U.equals(p.optString("type"))) continue;
            boolean expanded = open.contains(pid);
            TextView head = a.button((expanded ? "▼  " : "▶  ") + Library.nameOf(p), a.side, () -> {
                if (!open.remove(pid)) open.add(pid);
                render(a, pid);
            });
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(Theme.ACCENT);
            if (pid.equals(focusPlaylist)) focusMe = head;
            if (!expanded) continue;
            JSONArray cats = isActive ? a.categories : CACHE.get(key(a, pid));
            if (cats == null) {
                a.button("      Loading…", a.side, () -> { });
                fetch(a, p);
                continue;
            }
            Set<String> hidden = hiddenFor(a, p);
            entry(a, p, "All categories", "", isActive);
            int shown = 0;
            for (int k = 0; k < cats.length(); k++) {
                JSONObject c = cats.optJSONObject(k);
                if (c == null) continue;
                String id = c.optString("category_id"), title = c.optString("category_name", "Category");
                if (hidden.contains(id)) continue;
                shown++;
                entry(a, p, "› " + title, id, isActive).setOnLongClickListener(w -> {
                    new Dialogs.Builder(a).setTitle(title).setItems(new String[]{"Hide this category", "Cancel"}, (d, which) -> {
                        if (which != 0) return;
                        Set<String> h = hiddenFor(a, p);
                        h.add(id);
                        a.prefs.edit().putStringSet(hiddenKey(a, p), h).apply();
                        render(a, pid);
                    }).show();
                    return true;
                });
            }
            if (shown == 0 && cats.length() == 0) a.button("      No " + (a.mode.equals("series") ? "series" : "movies") + " here", a.side, () -> { });
        }
        // Tools at the bottom of the list (they work on the playing playlist).
        int hiddenCount = 0;
        Set<String> hidden = a.hiddenCategories();
        for (int i = 0; i < a.categories.length(); i++) {
            JSONObject c = a.categories.optJSONObject(i);
            if (c != null && hidden.contains(c.optString("category_id"))) hiddenCount++;
        }
        a.button(hiddenCount == 0 ? "◐ Hide / show categories" : "◐ Hide / show categories (" + hiddenCount + " hidden)", a.side, () -> { String m = a.mode; Manage.categories(a, null, m, () -> a.browse(m)); });
        if (focusMe != null) focusMe.requestFocus();
        LiveMenu.restyle(a); // rebuilt while shrunk beside the posters: keep the narrow look
    }

    /** One category under a playlist heading. */
    private static TextView entry(MainActivity a, JSONObject p, String label, String id, boolean isActive) {
        return a.button("    " + label, a.side, () -> {
            if (isActive) { a.loadItems(id); return; }
            Switching.show(a, p);
            a.switchPlaylist(p);
            open.add(p.optString("id"));
            a.resumeCategory = id;
            a.browse(a.mode);
        });
    }

    private static String key(MainActivity a, String pid) { return pid + "|" + a.mode; }

    private static String hiddenKey(MainActivity a, JSONObject p) { return Library.prefixOf(p) + "hidden_categories_" + a.mode; }

    private static Set<String> hiddenFor(MainActivity a, JSONObject p) {
        return new HashSet<>(a.prefs.getStringSet(hiddenKey(a, p), new HashSet<>()));
    }

    /** Fetches another Xtream playlist's movie or series categories in the background, then redraws the list. */
    private static void fetch(MainActivity a, JSONObject p) {
        String pid = p.optString("id"), mode = a.mode, k = key(a, pid);
        if (!LOADING.add(k)) return;
        int generation = a.screenGeneration;
        String url = p.optString("host") + "/player_api.php?username=" + a.enc(p.optString("user")) + "&password=" + a.enc(p.optString("pass"))
                + "&action=" + (mode.equals("vod") ? "get_vod_categories" : "get_series_categories");
        new Thread(() -> {
            JSONArray list;
            try {
                String text = Library.download(url, 30000).trim();
                list = text.startsWith("[") ? new JSONArray(text) : new JSONArray();
            } catch (Exception e) {
                list = null;
            }
            JSONArray got = list;
            a.runOnUiThread(() -> {
                LOADING.remove(k);
                if (a.isFinishing() || a.isDestroyed()) return;
                if (got == null) {
                    android.widget.Toast.makeText(a, "Couldn't load " + Library.nameOf(p) + " categories", android.widget.Toast.LENGTH_LONG).show();
                    open.remove(pid);
                } else CACHE.put(k, got);
                if (generation == a.screenGeneration && mode.equals(a.mode)) {
                    android.view.View f = a.getCurrentFocus();
                    boolean inMenu = f != null && f.getParent() == a.side;
                    render(a, inMenu ? pid : null);
                }
            });
        }).start();
    }
}
