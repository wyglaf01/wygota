package com.wygtv.tv;

import android.app.AlertDialog;
import android.widget.ListView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The TV guide's "Showing" pop-up: Favorites, antenna tuners and your channel groups at the top, then each playlist as a
 * heading (OK opens or closes it) with its own "All channels" and live categories underneath. Every switched-on
 * playlist is listed; a category from any of them opens in the guide without changing playlists.
 */
final class GuideSources {
    private GuideSources() {}

    /** Playlists opened in the pop-up (the playing one starts open). */
    private static Set<String> open;
    /** Live categories of playlists that aren't playing, by playlist id. */
    private static final Map<String, JSONArray> CACHE = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    private static AlertDialog shown;

    /** activeCats: the playing playlist's live categories, already loaded. */
    static void show(MainActivity a, JSONArray activeCats) { show(a, activeCats, -1); }

    private static void show(MainActivity a, JSONArray activeCats, int select) {
        String active = a.library.activeId();
        if (open == null) { open = new HashSet<>(); open.add(active); }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("★  Favorites");
        actions.add(() -> a.openGuideSource("fav", null));
        List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        Antenna.addGuideChoices(a, ids, names);
        JSONArray mine = VirtualCategories.all(a);
        for (int i = 0; i < mine.length(); i++) {
            JSONObject c = mine.optJSONObject(i);
            if (c != null && VirtualCategories.channels(a, c.optString("id")).length() > 0) { ids.add(c.optString("id")); names.add("◆  " + c.optString("name")); }
        }
        for (int i = 0; i < ids.size(); i++) { String id = ids.get(i); labels.add(names.get(i)); actions.add(() -> a.openGuideSource(id, null)); }

        JSONArray pls = a.library.inUse();
        boolean sawActive = false;
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null || Library.isHdhr(p)) continue;
            final String pid = p.optString("id");
            final boolean isActive = pid.equals(active);
            sawActive |= isActive;
            boolean expanded = open.contains(pid);
            final int headAt = labels.size();
            labels.add((expanded ? "▼  " : "▶  ") + Library.nameOf(p));
            actions.add(() -> { if (!open.remove(pid)) open.add(pid); show(a, activeCats, headAt); });
            if (!expanded) continue;
            JSONArray cats = isActive ? activeCats : CACHE.get(pid);
            if (cats == null) {
                labels.add("      Loading…");
                actions.add(() -> show(a, activeCats, headAt));
                fetch(a, p, activeCats, headAt);
                continue;
            }
            labels.add("      ▦  All channels");
            actions.add(pick(a, p, isActive, "all"));
            Set<String> hidden = new HashSet<>(a.prefs.getStringSet(isActive ? a.hiddenKey("live") : Library.prefixOf(p) + "hidden_categories_live", new HashSet<>()));
            for (int k = 0; k < cats.length(); k++) {
                JSONObject c = cats.optJSONObject(k);
                if (c == null || hidden.contains(c.optString("category_id"))) continue;
                labels.add("      ›  " + c.optString("category_name", "Category"));
                actions.add(pick(a, p, isActive, c.optString("category_id")));
            }
        }
        if (!sawActive) {
            // No saved playlists (a single login): its categories go straight in the list.
            labels.add("▦  All channels");
            actions.add(() -> a.openGuideSource("all", null));
            for (int k = 0; activeCats != null && k < activeCats.length(); k++) {
                JSONObject c = activeCats.optJSONObject(k);
                if (c == null || a.prefs.getStringSet(a.hiddenKey("live"), new HashSet<>()).contains(c.optString("category_id"))) continue;
                String id = c.optString("category_id");
                labels.add("›  " + c.optString("category_name", "Category"));
                actions.add(() -> a.openGuideSource(id, null));
            }
        }

        if (shown != null && shown.isShowing()) shown.dismiss();
        AlertDialog d = new Dialogs.Builder(a).setTitle("Show in the TV guide")
                .setItems(labels.toArray(new String[0]), (x, w) -> actions.get(w).run())
                .setNegativeButton("Cancel", null).show();
        shown = d;
        d.setOnDismissListener(x -> { if (shown == d) shown = null; });
        ListView lv = d.getListView();
        if (lv != null && select >= 0) lv.post(() -> { lv.requestFocus(); lv.setSelection(select); });
    }

    private static final String PREFIX = "plcat:";

    private static Runnable pick(MainActivity a, JSONObject p, boolean isActive, String src) {
        return () -> a.openGuideSource(isActive ? src : PREFIX + p.optString("id") + "|" + src, null);
    }

    /** A category's name in a playlist whose categories this pop-up has loaded, else "". */
    static String categoryName(String pid, String catId) {
        JSONArray cats = CACHE.get(pid);
        for (int k = 0; cats != null && catId != null && !catId.isEmpty() && k < cats.length(); k++) {
            JSONObject c = cats.optJSONObject(k);
            if (c != null && catId.equals(c.optString("category_id"))) return c.optString("category_name");
        }
        return "";
    }

    /** A guide choice for a category ("all" for every channel) of a playlist other than the one the app loads by default. */
    static boolean isOther(String src) { return src != null && src.startsWith(PREFIX); }

    /** Opens the full TV guide on such a choice: that playlist's channels, tagged with it, so nothing switches. */
    static void openOther(MainActivity a, String src, String focusId, boolean picked) {
        int bar = src.indexOf('|');
        JSONObject p = bar < 0 ? null : a.library.find(src.substring(PREFIX.length(), bar));
        if (p == null || !a.library.isOn(p)) { a.showGuide("all", focusId, false); return; }
        if (p.optString("id").equals(a.library.activeId())) { a.showGuide(src.substring(bar + 1), focusId, picked); return; }
        final String cat = src.substring(bar + 1);
        final int ticket = ++a.listGeneration;
        if (a.status != null) a.status.setText("Loading " + Library.nameOf(p) + " channels…");
        new Thread(() -> {
            JSONArray list;
            try { list = a.library.liveChannelsOf(p); } catch (Exception e) { list = null; }
            JSONArray kept = new JSONArray();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && (cat.equals("all") || cat.equals(o.optString("category_id")))) kept.put(o);
            }
            final JSONArray got = list == null ? null : kept;
            String catName = cat.equals("all") ? "All channels" : cat;
            JSONArray cats = CACHE.get(p.optString("id"));
            for (int k = 0; cats != null && k < cats.length(); k++) {
                JSONObject c = cats.optJSONObject(k);
                if (c != null && cat.equals(c.optString("category_id"))) catName = c.optString("category_name", catName);
            }
            final String title = Library.nameOf(p) + " › " + catName;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || ticket != a.listGeneration) return;
                if (got == null || got.length() == 0) {
                    Toast.makeText(a, got == null ? "Couldn't load " + Library.nameOf(p) + ". Showing all channels." : "That category is empty. Showing all channels.", Toast.LENGTH_LONG).show();
                    a.showGuide("all", focusId, false);
                    return;
                }
                a.items = got;
                a.category = src;
                a.categories = new JSONArray();
                try { a.categories.put(new JSONObject().put("category_id", src).put("category_name", title)); } catch (org.json.JSONException ignored) { }
                TvGuide.open(a, focusId);
            });
        }).start();
    }

    /** Loads a playlist's live categories in the background, then redraws the pop-up if it is still open. */
    private static void fetch(MainActivity a, JSONObject p, JSONArray activeCats, int headAt) {
        String pid = p.optString("id");
        if (!LOADING.add(pid)) return;
        new Thread(() -> {
            JSONArray list = new JSONArray();
            boolean ok = true;
            try {
                if (Library.M3U.equals(p.optString("type"))) {
                    for (String g : a.library.m3uOf(p).groups) list.put(new JSONObject().put("category_id", g).put("category_name", g));
                } else {
                    String text = Library.download(p.optString("host") + "/player_api.php?username=" + a.enc(p.optString("user"))
                            + "&password=" + a.enc(p.optString("pass")) + "&action=get_live_categories", 30000).trim();
                    if (text.startsWith("[")) list = new JSONArray(text);
                }
            } catch (Exception e) {
                ok = false;
            }
            final JSONArray got = list;
            final boolean fine = ok;
            a.runOnUiThread(() -> {
                LOADING.remove(pid);
                if (a.isFinishing() || a.isDestroyed()) return;
                if (fine) CACHE.put(pid, got);
                else {
                    open.remove(pid);
                    Toast.makeText(a, "Couldn't load " + Library.nameOf(p) + " categories", Toast.LENGTH_LONG).show();
                }
                if (shown != null && shown.isShowing()) show(a, activeCats, headAt);
            });
        }).start();
    }
}
