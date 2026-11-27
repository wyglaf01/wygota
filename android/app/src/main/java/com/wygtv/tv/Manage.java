package com.wygtv.tv;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
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
 * Settings → Manage playlists → "Turn playlists on or off", "Manage categories" and "Hide and show channels".
 * Both open a full-screen page (mode "manage"): a fixed menu bar at the top (Save, Tick all, Untick all,
 * Exit), groups on the left (Live TV / Movies / Series, or the channel categories) and a tick list on the
 * right where ticked = shown. Changes are kept across groups until Save. Back in a list jumps to the menu
 * bar; Back on the menu bar exits (asking to save first if anything changed). Uses the same per-playlist
 * prefs as the Live TV / Movies / Series menus: hiddenKey(section) and hiddenChannelsKey().
 */
final class Manage {
    private Manage() { }

    static final String[] SECTIONS = {"live", "vod", "series"};
    static final String[] SECTION_NAMES = {"Live TV", "Movies", "Series"};

    static int hiddenCategories(MainActivity a, String section) {
        return a.prefs.getStringSet(a.hiddenKey(section), new HashSet<>()).size();
    }

    /** Short summary for the Settings row, e.g. "Live TV 3 hidden  •  Movies all shown  •  Series 1 hidden". */
    static String categorySummary(MainActivity a) {
        StringBuilder b = new StringBuilder();
        int n = a.liveOnly() ? 1 : SECTIONS.length;
        for (int i = 0; i < n; i++) {
            if (b.length() > 0) b.append("  •  ");
            int h = hiddenCategories(a, SECTIONS[i]);
            b.append(SECTION_NAMES[i]).append(h == 0 ? " all shown" : " " + h + " hidden");
        }
        return b.toString();
    }

    static String channelSummary(MainActivity a) {
        int h = a.hiddenChannels().size();
        return h == 0 ? "no channels hidden" : h + (h == 1 ? " channel hidden" : " channels hidden");
    }

    // ---------- Categories ----------

    /** Opens the full-screen category page on the first playlist. */
    static void categories(MainActivity a, Runnable after) { categories(a, null, null, after); }

    static void categories(MainActivity a, JSONObject startAt, Runnable after) { categories(a, startAt, null, after); }

    /**
     * Opens the full-screen category page. The left column lists every playlist (HDHomeRun tuners aside) with its
     * Live TV / Movies / Series under it (just Live TV for M3U playlists and in Live-TV-only mode), so each
     * playlist's categories are hidden or shown separately. {@code startAt} picks the playlist to open on.
     */
    static void categories(MainActivity a, JSONObject startAt, String onlySection, Runnable after) {
        List<JSONObject> pls = new ArrayList<>();
        JSONArray all = a.library.all();
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p == null || Library.isHdhr(p)) continue;
            // Movies / Series only: M3U playlists have none.
            if (onlySection != null && !onlySection.equals("live") && Library.M3U.equals(p.optString("type"))) continue;
            pls.add(p);
        }
        if (pls.isEmpty()) { Menus.alert(a, "Add a playlist first."); return; }
        // One section (from the Movies or Series screen): one entry per playlist, no headings needed.
        boolean headings = pls.size() > 1 && onlySection == null;
        String startId = startAt != null ? startAt.optString("id") : a.library.activeId();
        List<Group> groups = new ArrayList<>();
        int start = -1;
        for (JSONObject p : pls) {
            String title = Library.nameOf(p);
            if (headings) groups.add(Group.heading("▤  " + title));
            boolean m3u = Library.M3U.equals(p.optString("type"));
            int n = a.liveOnly() || m3u ? 1 : SECTIONS.length;
            for (int i = 0; i < n; i++) {
                String section = SECTIONS[i], name = SECTION_NAMES[i];
                if (onlySection != null && !onlySection.equals(section)) continue;
                if (start < 0 && p.optString("id").equals(startId)) start = groups.size();
                Group g = new Group((headings ? "      " : "") + name + " categories", Library.prefixOf(p) + "hidden_categories_" + section, true,
                        got -> fetchCategories(a, p, section, got, (headings ? title + ": " : "This playlist has no ") + (headings ? "no " : "") + name + " categories."));
                g.name = headings ? name + " (" + title + ")" : name + " categories";
                if (onlySection != null && pls.size() > 1) { g = new Group("▤  " + title, g.key, true, g.loader); g.name = title; }
                groups.add(g);
            }
        }
        if (start < 0) start = headings ? 1 : 0;
        if (groups.isEmpty()) { Menus.alert(a, "No playlist has these categories."); return; }
        String heading = onlySection == null ? "MANAGE CATEGORIES" : "HIDE AND SHOW " + SECTION_NAMES[java.util.Arrays.asList(SECTIONS).indexOf(onlySection)].toUpperCase(java.util.Locale.ROOT) + " CATEGORIES";
        Page page = new Page(a, heading, "categories", groups, start, after);
        page.onSaved = () -> { a.otherLive.clear(); MasterSearch.clear(); };
        page.open();
    }

    /** Loads one playlist's Live TV / Movies / Series categories without switching to it. */
    private static void fetchCategories(MainActivity a, JSONObject p, String section, Got got, String empty) {
        new Thread(() -> {
            List<String> ids = new ArrayList<>(), titles = new ArrayList<>();
            boolean failed = false;
            try {
                if (Library.M3U.equals(p.optString("type"))) {
                    if (section.equals("live")) for (String g : a.library.m3uOf(p).groups) { ids.add(g); titles.add(g); }
                } else {
                    String url = p.optString("host") + "/player_api.php?username=" + a.enc(p.optString("user")) + "&password=" + a.enc(p.optString("pass"))
                            + "&action=get_" + section + "_categories";
                    String text = Library.download(url, 30000).trim();
                    JSONArray cats = text.startsWith("[") ? new JSONArray(text) : new JSONArray();
                    for (int j = 0; j < cats.length(); j++) {
                        JSONObject c = cats.optJSONObject(j);
                        if (c == null) continue;
                        ids.add(c.optString("category_id"));
                        titles.add(c.optString("category_name", "Category"));
                    }
                }
            } catch (Exception e) {
                failed = true;
            }
            String msg = failed ? "Couldn't load " + Library.nameOf(p) + ". Check its connection, then try again." : empty;
            a.runOnUiThread(() -> { if (!a.isFinishing()) got.run(ids, titles, msg); });
        }).start();
    }

    // ---------- Channels ----------

    /** Opens the full-screen channel page: Hidden channels, All channels and every Live TV category on the left. */
    static void channels(MainActivity a, Runnable after) {
        a.request("get_live_categories", o -> {
            JSONArray cats = o == null ? null : o.optJSONArray("list");
            String key = a.hiddenChannelsKey();
            List<Group> groups = new ArrayList<>();
            Page[] page = new Page[1];
            groups.add(new Group("⊘  Hidden channels", key, true, got -> loadChannels(a, "", got, page[0].original(key), "No channels are hidden.")));
            groups.add(new Group("▦  All channels", key, false, got -> loadChannels(a, "", got, null, "This playlist has no channels.")));
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("category_id"), name = c.optString("category_name", "Category");
                groups.add(new Group("›  " + name, key, false, got -> loadChannels(a, id, got, null, "No channels in " + name + ".")));
            }
            int start = a.hiddenChannels().isEmpty() ? 1 : 0;
            page[0] = new Page(a, "HIDE AND SHOW CHANNELS", "channels", groups, start, after);
            page[0].open();
        }, "Unable to load categories");
    }

    /**
     * The tick page for one Live TV category only (from holding OK on the category): ticked = shown. Only the
     * channels in that category are listed, so only they can be hidden or shown again here.
     */
    static void channelsIn(MainActivity a, String categoryId, String name, Runnable after) {
        String key = a.hiddenChannelsKey();
        List<Group> groups = new ArrayList<>();
        groups.add(new Group("›  " + name, key, false, got -> loadChannels(a, categoryId, got, null, "No channels in " + name + ".")));
        new Page(a, "HIDE AND SHOW CHANNELS  •  " + name.toUpperCase(java.util.Locale.ROOT), "channels", groups, 0, after).open();
    }

    /**
     * The tick page for one HDHomeRun tuner's channels (from its TV guide): ticked = shown. A hidden antenna channel
     * is left out everywhere the tuner's channels appear (its guide, Live TV, Search and channel up/down).
     */
    static void antennaChannels(MainActivity a, JSONObject tuner, Runnable after) {
        String key = Library.prefixOf(tuner) + "hidden_channels_live";
        List<Group> groups = new ArrayList<>();
        groups.add(new Group("📡  " + Antenna.nameOf(tuner), key, false, got -> new Thread(() -> {
            List<String> ids = new ArrayList<>(), names = new ArrayList<>();
            String msg = "This tuner has no channels.";
            try {
                JSONArray all = a.library.liveSearchList(tuner); // every channel, hidden ones included
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject c = all.optJSONObject(i);
                    if (c == null) continue;
                    String id = c.optString("stream_id");
                    if (id.isEmpty() || !seen.add(id)) continue;
                    ids.add(id);
                    String num = c.optString("num");
                    String name = c.optString("name", "Channel");
                    names.add(num.isEmpty() || name.startsWith(num) ? name : num + "  " + name);
                }
            } catch (Throwable e) { msg = "Couldn't reach the HDHomeRun at " + Menus.hostOf(tuner.optString("url")) + "."; }
            final String m = msg;
            a.runOnUiThread(() -> { if (!a.isFinishing()) got.run(ids, names, m); });
        }).start()));
        new Page(a, "HIDE AND SHOW CHANNELS  •  " + Antenna.nameOf(tuner).toUpperCase(java.util.Locale.ROOT), "channels", groups, 0, after).open();
    }

    /** Loads a category's channels (or every channel when categoryId is ""); onlyThese keeps just those ids. */
    private static void loadChannels(MainActivity a, String categoryId, Got got, Set<String> onlyThese, String empty) {
        String action = "get_live_streams" + (categoryId.isEmpty() ? "" : "&category_id=" + a.enc(categoryId));
        a.request(action, o -> {
            JSONArray list = o == null ? null : o.optJSONArray("list");
            List<String> ids = new ArrayList<>(), names = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("stream_id");
                if (id.isEmpty() || !seen.add(id)) continue;
                if (onlyThese != null && !onlyThese.contains(id)) continue;
                ids.add(id);
                names.add(c.optString("name", "Channel"));
            }
            got.run(ids, names, empty);
        }, "Unable to load channels");
    }

    // ---------- Playlists on / off ----------

    /** Short summary for the Settings row, e.g. "3 playlists, all on" or "2 on, 1 off". */
    static String playlistSummary(MainActivity a) {
        int all = a.library.all().length(), on = a.library.inUse().length();
        if (all == 0) return "no playlists";
        return on == all ? all + (all == 1 ? " playlist, on" : " playlists, all on") : on + " on, " + (all - on) + " off";
    }

    /**
     * Opens the full-screen tick page for every saved playlist and antenna tuner: ticked = on. A playlist
     * that is switched off is left out of Live TV, Movies, Series, the TV guides, Favorites and Search, and
     * is never downloaded, until it is ticked again. At least one has to stay on.
     */
    static void playlistsOnOff(MainActivity a, Runnable after) {
        List<Group> groups = new ArrayList<>();
        groups.add(new Group("Playlists", Library.OFF, true, got -> {
            JSONArray all = a.library.all();
            List<String> ids = new ArrayList<>(), names = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject p = all.optJSONObject(i);
                if (p == null) continue;
                ids.add(p.optString("id"));
                names.add((Library.isHdhr(p) ? "📡 " + Antenna.nameOf(p) : Library.nameOf(p)) + "   ·   " + Menus.describe(p));
            }
            got.run(ids, names, "No playlists saved yet.");
        }));
        Page page = new Page(a, "TURN PLAYLISTS ON OR OFF", "playlists", groups, 0, () -> { applyPlaylistsOnOff(a); after.run(); });
        page.onWord = "on";
        page.offWord = "off";
        page.check = off -> a.library.all().length() > 0 && off.size() >= a.library.all().length() ? "Keep at least one playlist on." : null;
        page.open();
    }

    /** After the on/off list is saved: move off a playlist that was switched off and forget lists built from the old set. */
    static void applyPlaylistsOnOff(MainActivity a) {
        JSONObject next = a.library.replacementForActive();
        MasterSearch.clear();
        LiveMenu.forgetOthers();
        // Switched-off playlists are never loaded or updated; their saved lists are deleted to free space.
        JSONArray all = a.library.all();
        for (int i = 0; i < all.length(); i++) { JSONObject p = all.optJSONObject(i); if (p != null && !a.library.isOn(p)) { a.library.freeSpaceOf(p); a.otherLive.remove(p.optString("id")); } }
        if (next != null) a.switchPlaylist(next);
        else if (a.activeLive != null && a.liveIndex != null) a.rebuildSearchIndex();
    }

    // ---------- The full-screen tick page ----------

    /** Optional rule checked before saving: returns what is wrong with the unticked set, or null when it can be saved. */
    interface Check { String problem(Set<String> unticked); }

    interface Got { void run(List<String> ids, List<String> names, String emptyMessage); }
    interface Loader { void load(Got got); }

    /** One entry on the left: what it is called, which hidden-set pref it edits, and how to load its rows. */
    static final class Group {
        final String label, key; final boolean showCount; final Loader loader;
        /** The name used in the info line and on Tick all / Untick all. */
        String name;
        Group(String label, String key, boolean showCount, Loader loader) {
            this.label = label; this.key = key; this.showCount = showCount; this.loader = loader;
            this.name = label.replaceAll("^[^A-Za-z0-9]+", "");
        }
        /** A playlist name on the left: not selectable, just groups the entries under it. */
        static Group heading(String label) { return new Group(label, null, false, null); }
        boolean isHeading() { return loader == null; }
    }

    /** The page that is open now (mode "manage"), for the Back key. */
    static Page page;

    /** Back on the manage page: from a list, jump to the menu bar; from the menu bar, exit (asking to save first). */
    static boolean back(MainActivity a) {
        Page p = page;
        if (p == null || p.a != a) return false;
        p.back();
        return true;
    }

    static final class Page {
        final MainActivity a; final String title, what; final List<Group> groups; final Runnable after;
        /** Runs after a Save that changed something (e.g. to drop cached lists of other playlists). */
        Runnable onSaved;
        /** Hidden ids per pref key: as saved, and as edited on this page. */
        final Map<String, Set<String>> saved = new HashMap<>(), edited = new HashMap<>();
        final Handler ui = new Handler(Looper.getMainLooper());
        int current, loadGeneration; boolean focusListOnLoad = true;
        /** How the info line and Save message name ticked and unticked rows. */
        String onWord = "shown", offWord = "hidden";
        Check check;
        List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        LinearLayout bar; TextView save, info, tickBtn, untickBtn; ListView left, right; BaseAdapter leftAdapter, rightAdapter;

        Page(MainActivity a, String title, String what, List<Group> groups, int start, Runnable after) {
            this.a = a; this.title = title; this.what = what; this.groups = groups; this.after = after; this.current = start;
            for (Group g : groups) if (!g.isHeading() && !saved.containsKey(g.key)) {
                saved.put(g.key, new HashSet<>(a.prefs.getStringSet(g.key, new HashSet<>())));
                edited.put(g.key, new HashSet<>(saved.get(g.key)));
            }
        }

        Set<String> original(String key) { return saved.get(key); }
        Set<String> hidden() { return edited.get(groups.get(current).key); }

        boolean changed() {
            for (String k : saved.keySet()) if (!saved.get(k).equals(edited.get(k))) return true;
            return false;
        }

        void open() {
            page = this;
            LivePreview.release();
            a.mode = "manage";
            a.screenGeneration++;
            a.listGeneration++;
            LinearLayout root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(a.dp(24), a.dp(14), a.dp(24), a.dp(12));
            root.setBackground(new SteampunkBackdrop(a.getResources(), R.drawable.steampunk_panel, false));
            a.setContentView(root);

            TextView h = a.label(title, 22);
            h.setTypeface(null, Typeface.BOLD);
            h.setTextColor(a.BRASS);
            root.addView(h, new LinearLayout.LayoutParams(-1, a.dp(36)));

            // The fixed menu bar: it never scrolls away, and Back from a list lands here.
            bar = new LinearLayout(a);
            root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            save = a.button("✓ Save", bar, this::save);
            TextView tickAll = tickBtn = a.button("☑ Tick all", bar, () -> tickAll(true));
            TextView untickAll = untickBtn = a.button("☐ Untick all", bar, () -> tickAll(false));
            TextView exit = a.button("✕ Exit", bar, this::exit);
            for (TextView t : new TextView[]{save, tickAll, untickAll, exit}) { t.setLayoutParams(a.rowLp()); t.setTextSize(16); t.setGravity(Gravity.CENTER); t.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7)); }

            info = a.label("", 14);
            info.setTextColor(Theme.MUTED);
            info.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
            root.addView(info, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout panes = new LinearLayout(a);
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
            left = list();
            right = list();
            if (groups.size() > 1) panes.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
            panes.addView(right, new LinearLayout.LayoutParams(0, -1, groups.size() > 1 ? 2 : 1));

            leftAdapter = new Rows() {
                @Override public int getCount() { return groups.size(); }
                @Override public View getView(int i, View v, ViewGroup parent) {
                    Group g = groups.get(i);
                    if (g.isHeading()) {
                        TextView t = text(v, g.label);
                        t.setBackground(null);
                        t.setTextColor(a.BRASS);
                        return t;
                    }
                    int n = g.showCount ? edited.get(g.key).size() : -1;
                    TextView t = text(v, g.label + (n > 0 ? "   (" + n + " " + offWord + ")" : ""));
                    t.setBackground(i == current ? a.bg(a.PANEL) : null);
                    t.setTextColor(i == current ? Theme.TEXT : Theme.MUTED);
                    return t;
                }
                // Playlist names are headings: the remote skips over them.
                @Override public boolean areAllItemsEnabled() { return false; }
                @Override public boolean isEnabled(int i) { return !groups.get(i).isHeading(); }
            };
            left.setAdapter(leftAdapter);
            left.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    ui.removeCallbacksAndMessages(null);
                    // Not once focus is on the top bar: passing over entries on the way up to the top bar must not switch
                    // the list, or Tick all / Untick all would hit a different category (e.g. All channels).
                    if (pos != current) ui.postDelayed(() -> { View f = a.getCurrentFocus(); if (f == null || f.getParent() != bar) show(pos, false); }, 350);
                }
                @Override public void onNothingSelected(AdapterView<?> p) { }
            });
            left.setOnItemClickListener((p, v, pos, id) -> {
                ui.removeCallbacksAndMessages(null);
                if (pos != current) show(pos, true); else if (!ids.isEmpty()) right.requestFocus();
            });

            rightAdapter = new Rows() {
                @Override public int getCount() { return ids.size(); }
                @Override public View getView(int i, View v, ViewGroup parent) {
                    boolean shown = !hidden().contains(ids.get(i));
                    TextView[] t = new TextView[1];
                    LinearLayout row = checkRow(v, t);
                    ((CheckBox) row.getChildAt(0)).setChecked(shown);
                    t[0].setText(names.get(i));
                    t[0].setTextColor(shown ? Theme.TEXT : Theme.MUTED);
                    return row;
                }
            };
            right.setAdapter(rightAdapter);
            right.setOnItemClickListener((p, v, pos, id) -> {
                String rowId = ids.get(pos);
                if (!hidden().remove(rowId)) hidden().add(rowId);
                refresh();
            });

            left.setSelection(current);
            show(current, true);
        }

        /** A list with the theme's focus highlight; CH+/CH- page through it. */
        ListView list() {
            ListView lv = new ListView(a);
            lv.setSelector(a.bg(Theme.FOCUS));
            lv.setDrawSelectorOnTop(false);
            lv.setDivider(null);
            lv.setDividerHeight(a.dp(1));
            lv.setItemsCanFocus(false);
            lv.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            lv.setClipToPadding(false);
            lv.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key != KeyEvent.KEYCODE_CHANNEL_DOWN && key != KeyEvent.KEYCODE_CHANNEL_UP) return false;
                int n = lv.getCount();
                if (n == 0) return true;
                int step = Math.max(1, lv.getChildCount() - 1) * (key == KeyEvent.KEYCODE_CHANNEL_DOWN ? 1 : -1);
                lv.setSelection(Math.max(0, Math.min(n - 1, lv.getSelectedItemPosition() + step)));
                return true;
            });
            return lv;
        }

        abstract static class Rows extends BaseAdapter {
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }
        }

        TextView text(View recycled, String s) {
            TextView t = recycled instanceof TextView ? (TextView) recycled : a.label("", 16);
            t.setPadding(a.dp(14), a.dp(6), a.dp(10), a.dp(6));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setText(s);
            return t;
        }

        LinearLayout checkRow(View recycled, TextView[] out) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(a.dp(8), 0, a.dp(10), 0);
                CheckBox box = new CheckBox(a);
                box.setFocusable(false);
                box.setClickable(false);
                box.setButtonTintList(ColorStateList.valueOf(Theme.ACCENT));
                // Compact rows so more channels fit on screen: no built-in minimum height, a slightly smaller box.
                box.setMinimumHeight(0);
                box.setMinHeight(0);
                box.setPadding(0, 0, 0, 0);
                box.setScaleX(0.8f);
                box.setScaleY(0.8f);
                row.addView(box, new LinearLayout.LayoutParams(a.dp(30), a.dp(30)));
                TextView t = a.label("", 16);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(a.dp(6), 0, 0, 0);
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            }
            out[0] = (TextView) row.getChildAt(1);
            return row;
        }

        /** Loads group {@code i} into the tick list; edits so far are kept. */
        void show(int i, boolean focusList) {
            current = i;
            focusListOnLoad = focusList;
            int gen = ++loadGeneration;
            ids = new ArrayList<>(); names = new ArrayList<>();
            rightAdapter.notifyDataSetChanged();
            leftAdapter.notifyDataSetChanged();
            info.setText("Loading " + groups.get(i).name + "…");
            groups.get(i).loader.load((newIds, newNames, empty) -> {
                if (page != this || gen != loadGeneration) return;
                ids = newIds; names = newNames;
                right.setSelection(0);
                refresh();
                if (ids.isEmpty()) info.setText(empty + (changed() ? "   •   unsaved changes" : ""));
                if (focusListOnLoad) { focusListOnLoad = false; (ids.isEmpty() ? (groups.size() > 1 ? left : save) : right).requestFocus(); }
            });
        }

        void refresh() {
            rightAdapter.notifyDataSetChanged();
            leftAdapter.notifyDataSetChanged();
            int on = 0;
            Set<String> h = hidden();
            for (String id : ids) if (!h.contains(id)) on++;
            String name = groups.get(current).name;
            info.setText(name + "   •   " + on + " " + onWord + ", " + (ids.size() - on) + " " + offWord + "   •   ticked are " + onWord + ", OK ticks or unticks"
                    + (changed() ? "   •   unsaved changes" : "") + "   •   Back = menu bar");
            save.setText(changed() ? "✓ Save changes" : "✓ Save");
            // Tick all / Untick all change only the list on the right, so say which one.
            String scope = groups.size() > 1 ? " in " + shortName(name) : "";
            tickBtn.setText("☑ Tick all" + scope);
            untickBtn.setText("☐ Untick all" + scope);
        }

        static String shortName(String name) { return name.length() > 18 ? name.substring(0, 17) + "…" : name; }

        void tickAll(boolean show) {
            if (ids.isEmpty()) return;
            Set<String> h = hidden();
            for (String id : ids) { if (show) h.remove(id); else h.add(id); }
            refresh();
        }

        void back() {
            View f = a.getCurrentFocus();
            boolean inBar = f != null && f.getParent() == bar;
            if (inBar) exit(); else save.requestFocus();
        }

        void exit() {
            if (!changed()) { close(); return; }
            new Dialogs.Builder(a).setTitle("Save your changes?")
                    .setMessage("You ticked or unticked some " + what + " but haven't saved.")
                    .setPositiveButton("Save", (d, w) -> save())
                    .setNegativeButton("Don't save", (d, w) -> close())
                    .setNeutralButton("Keep editing", null).show();
        }

        void save() {
            if (check != null) for (String k : edited.keySet()) {
                String problem = check.problem(edited.get(k));
                if (problem != null) { Toast.makeText(a, problem, Toast.LENGTH_LONG).show(); return; }
            }
            int hid = 0, back = 0;
            android.content.SharedPreferences.Editor e = a.prefs.edit();
            for (String k : saved.keySet()) {
                Set<String> was = saved.get(k), now = edited.get(k);
                if (was.equals(now)) continue;
                for (String id : now) if (!was.contains(id)) hid++;
                for (String id : was) if (!now.contains(id)) back++;
                e.putStringSet(k, new HashSet<>(now));
            }
            e.apply();
            if ((hid > 0 || back > 0) && onSaved != null) onSaved.run();
            String msg = hid == 0 && back == 0 ? "No changes" : (hid > 0 ? hid + " " + offWord : "") + (hid > 0 && back > 0 ? ", " : "") + (back > 0 ? back + " " + onWord + " again" : "");
            Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
            close();
        }

        void close() {
            ui.removeCallbacksAndMessages(null);
            if (page == this) page = null;
            after.run();
        }
    }
}
