package com.wygtv.tv;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Your own Live TV categories ("virtual" categories). Each has a name and a list of channels, and a channel can come
 * from any saved playlist: it remembers which one, and playing it switches to that playlist first. They are saved
 * once for the whole app (pref "virtual_categories"), so they show whichever playlist is active and are in backups.
 */
final class VirtualCategories {
    static final String PREF = "virtual_categories", PREFIX = "vc_";

    private VirtualCategories() { }

    static boolean isVirtual(String categoryId) { return categoryId != null && categoryId.startsWith(PREFIX); }

    static JSONArray all(MainActivity a) {
        try { return new JSONArray(a.prefs.getString(PREF, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    private static void save(MainActivity a, JSONArray list) { a.prefs.edit().putString(PREF, list.toString()).apply(); }

    static JSONObject find(MainActivity a, String id) {
        JSONArray l = all(a);
        for (int i = 0; i < l.length(); i++) { JSONObject c = l.optJSONObject(i); if (c != null && id.equals(c.optString("id"))) return c; }
        return null;
    }

    static String name(MainActivity a, String id) { JSONObject c = find(a, id); return c == null ? "My category" : c.optString("name", "My category"); }

    /** Replaces the category with the same id. */
    private static void put(MainActivity a, JSONObject cat) {
        JSONArray l = all(a), out = new JSONArray();
        for (int i = 0; i < l.length(); i++) { JSONObject c = l.optJSONObject(i); if (c != null) out.put(c.optString("id").equals(cat.optString("id")) ? cat : c); }
        save(a, out);
    }

    /** All channels in the category, each tagged with the playlist it came from. */
    static JSONArray channels(MainActivity a, String id) {
        JSONObject c = find(a, id);
        JSONArray ch = c == null ? null : c.optJSONArray("channels"), out = new JSONArray();
        java.util.Set<String> off = a.library.offIds();
        // Channels from a switched-off playlist stay saved, but don't show until it is switched back on.
        for (int i = 0; ch != null && i < ch.length(); i++) { JSONObject o = ch.optJSONObject(i); if (o != null && !off.contains(o.optString("playlist")) && !a.library.hiddenAntenna(o.optString("playlist"), o.optString("stream_id"))) out.put(o); }
        return out;
    }

    /** Only the channels that belong to the given playlist (what CH+/CH- and the TV guide can use). */
    static JSONArray channelsFrom(MainActivity a, String id, String playlistId) {
        JSONArray all = channels(a, id), out = new JSONArray();
        for (int i = 0; i < all.length(); i++) { JSONObject o = all.optJSONObject(i); if (o != null && playlistId.equals(o.optString("playlist"))) out.put(o); }
        return out;
    }

    private static boolean contains(JSONObject cat, String playlistId, String streamId) {
        JSONArray ch = cat.optJSONArray("channels");
        for (int i = 0; ch != null && i < ch.length(); i++) {
            JSONObject o = ch.optJSONObject(i);
            if (o != null && playlistId.equals(o.optString("playlist")) && streamId.equals(o.optString("stream_id"))) return true;
        }
        return false;
    }

    interface Done { void run(JSONObject category); }

    /** Asks for a name and creates an empty category. */
    static void create(MainActivity a, Done then) {
        askName(a, "New Channel Group", "", name -> {
            JSONObject cat = new JSONObject();
            try { cat.put("id", PREFIX + UUID.randomUUID().toString()).put("name", name).put("channels", new JSONArray()); } catch (JSONException ignored) { }
            JSONArray l = all(a);
            l.put(cat);
            save(a, l);
            Toast.makeText(a, "Created \"" + name + "\". Add channels with OK on any channel or search result.", Toast.LENGTH_LONG).show();
            if (then != null) then.run(cat);
        });
    }

    /** Settings → Channel groups: make a new group, or rename / delete one. */
    static void manage(MainActivity a, Runnable after) {
        JSONArray l = all(a);
        String[] names = new String[l.length() + 1];
        names[0] = "+ New Channel Group";
        for (int i = 0; i < l.length(); i++) { JSONObject c = l.optJSONObject(i); names[i + 1] = "◆ " + (c == null ? "" : c.optString("name")); }
        new Dialogs.Builder(a).setTitle("Channel groups").setItems(names, (d, w) -> {
            if (w == 0) { create(a, c -> after.run()); return; }
            JSONObject c = l.optJSONObject(w - 1);
            if (c == null) return;
            String id = c.optString("id");
            new Dialogs.Builder(a).setTitle(c.optString("name")).setItems(new String[]{"Rename", "Delete", "Cancel"}, (d2, w2) -> {
                if (w2 == 0) rename(a, id, after);
                else if (w2 == 1) delete(a, id, after);
            }).show();
        }).setNegativeButton("Close", null).show();
    }

    static void rename(MainActivity a, String id, Runnable then) {
        JSONObject cat = find(a, id);
        if (cat == null) return;
        askName(a, "Rename channel group", cat.optString("name"), name -> {
            try { cat.put("name", name); } catch (JSONException ignored) { }
            put(a, cat);
            if (then != null) then.run();
        });
    }

    static void delete(MainActivity a, String id, Runnable then) {
        new Dialogs.Builder(a).setTitle("Delete \"" + name(a, id) + "\"?")
                .setMessage("Only the category goes. The channels stay in their playlists.")
                .setPositiveButton("Delete", (d, w) -> {
                    JSONArray l = all(a), out = new JSONArray();
                    for (int i = 0; i < l.length(); i++) { JSONObject c = l.optJSONObject(i); if (c != null && !c.optString("id").equals(id)) out.put(c); }
                    save(a, out);
                    if (then != null) then.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private interface Named { void run(String name); }

    private static void askName(MainActivity a, String title, String current, Named then) {
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setHint("Category name (e.g. Sports)");
        field.setText(current);
        field.setSelection(current.length());
        FrameLayout box = new FrameLayout(a);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        box.addView(field);
        new Dialogs.Builder(a).setTitle(title).setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) { Toast.makeText(a, "Type a name first.", Toast.LENGTH_SHORT).show(); return; }
                    then.run(name);
                })
                .setNegativeButton("Cancel", null).show();
    }

    /**
     * "Add to channel group…" for a channel of the active playlist: lists your categories (✓ = already in it) plus
     * "New category…". Picking a ticked one takes the channel out again.
     */
    static void addDialog(MainActivity a, JSONObject item) {
        String playlist = item.optString("playlist", "").isEmpty() ? a.library.activeId() : item.optString("playlist"), sid = item.optString("stream_id");
        JSONArray l = all(a);
        List<JSONObject> cats = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) {
            JSONObject c = l.optJSONObject(i);
            if (c == null) continue;
            cats.add(c);
            labels.add((contains(c, playlist, sid) ? "✓  " : "      ") + c.optString("name"));
        }
        labels.add("+  New category…");
        new Dialogs.Builder(a).setTitle("Add \"" + item.optString("name", "channel") + "\" to")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w == cats.size()) { create(a, cat -> toggle(a, cat, item, playlist)); return; }
                    toggle(a, cats.get(w), item, playlist);
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Makes a new channel group (asks its name) with this channel in it. */
    static void addToNew(MainActivity a, JSONObject item) {
        String playlist = item.optString("playlist", "").isEmpty() ? a.library.activeId() : item.optString("playlist");
        create(a, cat -> toggle(a, cat, item, playlist));
    }

    private static void toggle(MainActivity a, JSONObject cat, JSONObject item, String playlist) {
        String sid = item.optString("stream_id");
        boolean had = contains(cat, playlist, sid);
        if (had) removeFrom(a, cat.optString("id"), playlist, sid);
        else {
            JSONObject keep = new JSONObject();
            try {
                for (String k : new String[]{"stream_id", "name", "container_extension", "stream_icon", "epg_channel_id"}) if (item.has(k)) keep.put(k, item.opt(k));
                keep.put("playlist", playlist);
                JSONArray ch = cat.optJSONArray("channels");
                if (ch == null) { ch = new JSONArray(); cat.put("channels", ch); }
                ch.put(keep);
            } catch (JSONException ignored) { }
            put(a, cat);
        }
        Toast.makeText(a, (had ? "Removed from \"" : "Added to \"") + cat.optString("name") + "\"", Toast.LENGTH_SHORT).show();
    }

    static void removeFrom(MainActivity a, String id, String playlist, String sid) {
        JSONObject cat = find(a, id);
        if (cat == null) return;
        JSONArray ch = cat.optJSONArray("channels"), out = new JSONArray();
        for (int i = 0; ch != null && i < ch.length(); i++) {
            JSONObject o = ch.optJSONObject(i);
            if (o != null && !(playlist.equals(o.optString("playlist")) && sid.equals(o.optString("stream_id")))) out.put(o);
        }
        try { cat.put("channels", out); } catch (JSONException ignored) { }
        put(a, cat);
    }

    /** OK on a channel inside one of your categories. */
    static void select(MainActivity a, JSONObject item, String categoryId) {
        String title = item.optString("name", "Untitled"), playlist = item.optString("playlist");
        JSONObject p = a.library.find(playlist);
        String from = p == null ? "a removed playlist" : p.optString("title").isEmpty() ? Menus.hostOf(p.optString(Library.M3U.equals(p.optString("type")) ? "url" : "host")) : p.optString("title");
        new Dialogs.Builder(a).setTitle(title + "   (" + from + ")")
                .setItems(new String[]{"Play", "Program guide", "Remove from \"" + name(a, categoryId) + "\"", "Close"}, (d, w) -> {
                    if (w == 0 || w == 1) {
                        if (!useItsPlaylist(a, item)) return;
                        String sid = item.optString("stream_id"), ext = item.optString("container_extension", "ts");
                        if (w == 0) { a.zapList = channelsFrom(a, categoryId, playlist); a.play("live", sid, ext.isEmpty() ? "ts" : ext, title); }
                        else a.guide(sid, title);
                    } else if (w == 2) {
                        removeFrom(a, categoryId, playlist, item.optString("stream_id"));
                        a.loadItems(categoryId);
                    }
                }).show();
    }

    /** OK on a channel in the mini guide: switch to its playlist if needed and play, zapping through this category. */
    static void play(MainActivity a, JSONObject item, String categoryId) {
        if (!useItsPlaylist(a, item)) return;
        String sid = item.optString("stream_id"), ext = item.optString("container_extension", "ts");
        a.zapList = channelsFrom(a, categoryId, item.optString("playlist"));
        a.play("live", sid, ext.isEmpty() ? "ts" : ext, item.optString("name", "Untitled"));
    }

    /** Switches to the channel's playlist when it isn't the active one, keeping the category open. False if that playlist is gone. */
    static boolean useItsPlaylist(MainActivity a, JSONObject item) {
        String playlist = item.optString("playlist");
        if (playlist.isEmpty() || playlist.equals(a.library.activeId())) return true;
        JSONObject p = a.library.find(playlist);
        if (p == null) { Menus.alert(a, "This channel's playlist was removed, so it can't play. Remove it from the category."); return false; }
        String cat = a.category;
        JSONArray items = a.items;
        a.switchPlaylist(p);
        a.category = cat;
        a.items = items;
        return true;
    }
}
