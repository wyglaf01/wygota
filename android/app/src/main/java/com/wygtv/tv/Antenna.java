package com.wygtv.tv;

import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Antenna tuners (HDHomeRun, or a legacy Tablo: see {@link Tablo}) as a category. A tuner is added in Settings and saved like a playlist (see {@link Hdhr}),
 * but it shows in Live TV as its own category, "📡 Antenna TV" (or the name you give the tuner), next to Favorites and your categories, instead
 * of in the playlist list. Its channels play the same way a favorite from another playlist does: the tuner
 * becomes the active source while it plays, and Home, Movies and Series switch back to your main playlist.
 */
final class Antenna {
    /** Category id prefix: "antenna_" + the tuner's playlist id. */
    static final String PREFIX = "antenna_";
    /** The last playlist that wasn't a tuner, so Home, Movies and Series can go back to it. */
    static final String MAIN_PLAYLIST = "main_playlist";

    private Antenna() { }

    static boolean isAntenna(String categoryId) { return categoryId != null && categoryId.startsWith(PREFIX); }

    /** Switched-on tuners, in the order they were added (side menu, Home, guides). */
    static List<JSONObject> tuners(MainActivity a) { return tuners(a, a.library.inUse()); }

    /** Every saved tuner, switched off or not (Settings > HDHomeRun antenna TV). */
    static List<JSONObject> allTuners(MainActivity a) { return tuners(a, a.library.all()); }

    private static List<JSONObject> tuners(MainActivity a, JSONArray all) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) { JSONObject p = all.optJSONObject(i); if (Library.isHdhr(p)) out.add(p); }
        return out;
    }

    /** Name a new HDHomeRun tuner gets automatically. */
    static final String DEFAULT_NAME = "Antenna TV";

    /** The tuner's name with its first letter capitalized, or "Antenna TV" for tuners that were left with no name or the device's own name. */
    static String nameOf(JSONObject tuner) {
        String t = tuner == null ? "" : tuner.optString("title").trim();
        if (t.isEmpty() || t.startsWith("HDHomeRun")) return DEFAULT_NAME;
        return t.substring(0, 1).toUpperCase(java.util.Locale.getDefault()) + t.substring(1); // "local" shows as "Local"
    }

    /** "HDHomeRun" or "Tablo": the kind of tuner, for messages. */
    static String kindOf(JSONObject tuner) { return Library.isTablo(tuner) ? "Tablo" : "HDHomeRun"; }

    /** "📡 Antenna TV", or whatever name the tuner was given. */
    static String label(MainActivity a, JSONObject tuner) {
        return "📡 " + nameOf(tuner);
    }

    /** Adds each tuner ("📡 Antenna TV") to the TV guide's "Showing" choices. */
    static void addGuideChoices(MainActivity a, List<String> ids, List<String> names) {
        for (JSONObject t : tuners(a)) { ids.add(PREFIX + t.optString("id")); names.add(label(a, t)); }
    }

    /** Opens the full TV guide on a tuner's channels. Falls back to all channels when the tuner is gone or can't be reached. */
    static void openGuide(MainActivity a, String src, String focusId, boolean picked) {
        JSONObject tuner = a.library.find(src.substring(PREFIX.length()));
        if (tuner == null) { a.showGuide("all", focusId, false); return; }
        final int ticket = ++a.listGeneration;
        if (a.status != null) a.status.setText("Loading antenna channels…");
        new Thread(() -> {
            JSONArray list;
            try { list = a.library.liveChannelsOf(tuner); } catch (Exception e) { list = null; }
            final JSONArray got = list;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || ticket != a.listGeneration) return;
                if (got == null || got.length() == 0) {
                    Toast.makeText(a, got == null ? "Couldn't reach the " + kindOf(tuner) + " at " + Menus.hostOf(tuner.optString("url")) + ". Showing all channels."
                            : nameOf(tuner) + " has no channels. Showing all channels.", Toast.LENGTH_LONG).show();
                    a.showGuide("all", focusId, false);
                    return;
                }
                a.items = got;
                a.category = src;
                a.categories = new JSONArray();
                try { a.categories.put(new JSONObject().put("category_id", src).put("category_name", label(a, tuner))); } catch (org.json.JSONException ignored) { }
                TvGuide.open(a, focusId);
            });
        }).start();
    }

    /** Shows the tuner's channels in the mini guide. */
    static void show(MainActivity a, String categoryId) {
        JSONObject tuner = a.library.find(categoryId.substring(PREFIX.length()));
        if (tuner == null) { a.loadItems("fav"); return; }
        final int ticket = ++a.listGeneration;
        a.category = categoryId;
        if (a.status != null) a.status.setText("Loading antenna channels…");
        new Thread(() -> {
            JSONArray list;
            try { list = a.library.liveChannelsOf(tuner); } catch (Exception e) { list = null; }
            final JSONArray got = list;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || ticket != a.listGeneration) return;
                a.updateStatus();
                if (got == null) {
                    Menus.alert(a, "Couldn't reach the " + kindOf(tuner) + " at " + Menus.hostOf(tuner.optString("url"))
                            + ". Check it's on, or change its address in Settings > " + kindOf(tuner) + " antenna TV.");
                    a.items = new JSONArray();
                } else a.items = got;
                a.showItems();
            });
        }).start();
    }

    /** OK on an antenna channel: tune it, with CH+/CH- walking through the tuner's channels. */
    static void play(MainActivity a, JSONObject item) {
        JSONArray zap = a.items;
        if (!VirtualCategories.useItsPlaylist(a, item)) return;
        a.zapList = zap;
        String ext = item.optString("container_extension", "ts");
        a.play("live", item.optString("stream_id"), ext.isEmpty() ? "ts" : ext, item.optString("name", "Channel"));
    }

    /** Long-press OK on an antenna channel. */
    static void select(MainActivity a, JSONObject item) {
        String title = item.optString("name", "Channel"), sid = item.optString("stream_id");
        JSONObject tuner = a.library.find(item.optString("playlist"));
        String pre = tuner == null ? "" : Library.prefixOf(tuner);
        boolean fav = !pre.isEmpty() && a.isFavoriteIn(pre, "live", sid);
        new Dialogs.Builder(a).setTitle(title).setItems(new String[]{"Play", fav ? "Remove Favorite" : "Add Favorite", "Program guide", "Add to channel group…", "Close"}, (d, w) -> {
            if (w == 0) play(a, item);
            else if (w == 1 && !pre.isEmpty()) {
                boolean added = a.toggleFavoriteIn(pre, "live", sid, title, "ts");
                Toast.makeText(a, added ? "Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
            } else if (w == 2) {
                if (!VirtualCategories.useItsPlaylist(a, item)) return;
                a.guide(sid, title);
            } else if (w == 3) VirtualCategories.addDialog(a, item);
        }).show();
    }

    /** Remembers the last non-tuner playlist the app switched to. */
    static void remember(MainActivity a, JSONObject p) {
        if (p != null && !Library.isHdhr(p)) a.prefs.edit().putString(MAIN_PLAYLIST, p.optString("id")).apply();
    }

    /**
     * When a tuner is active only because an antenna channel played, Home, Movies and Series go back to the main
     * playlist. Nothing changes when the tuner is the only source.
     */
    static void restoreMain(MainActivity a) {
        if (!a.library.activeIsHdhr()) return;
        JSONObject main = a.library.find(a.prefs.getString(MAIN_PLAYLIST, ""));
        if (main != null && !a.library.isOn(main)) main = null;
        if (main == null) {
            JSONArray all = a.library.inUse();
            for (int i = 0; i < all.length() && main == null; i++) { JSONObject p = all.optJSONObject(i); if (p != null && !Library.isHdhr(p)) main = p; }
        }
        if (main != null) a.switchPlaylist(main);
    }
}
