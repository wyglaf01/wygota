package com.wygtv.tv;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Search (Home and the side menu): one search box that looks through every saved playlist at once, with the
 * results grouped side by side into Live TV, Movies and Series. The box is ready to type in right away; each
 * playlist's lists load in the background (one at a time) only once something is typed, and are kept for the session. OK on a channel opens the usual channel options (Play,
 * Favorites, ...); OK on a movie or series opens its details page, switching playlist first when needed.
 */
final class MasterSearch {
    static final String MODE = "msearch";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final int LIMIT = 100;
    private static final String[] TYPES = {"live", "vod", "series"};
    private static final String[] TITLES = {"▣  Live TV", "▶  Movies", "▤  Series"};

    /** Loaded lists by "playlistId|type", with their search keys. Only touched on the UI thread. */
    private static final Map<String, JSONArray> LISTS = new HashMap<>();
    private static final Map<String, String[]> KEYS = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>(), FAILED = new HashSet<>();
    /** Lists waiting for the loader thread, which reads them one after another to keep memory low. */
    private static final java.util.ArrayDeque<Object[]> QUEUE = new java.util.ArrayDeque<>();
    private static boolean working;
    /** Set when a list didn't fit in memory: the rest aren't loaded this session. */
    private static boolean outOfMemory;
    private static String query = "";
    /** The row picked last ("type|playlist|id"), focused again when coming back from the player or details. */
    private static String resumeTag;
    private static Screen screen;

    private static final class Screen {
        MainActivity a;
        int generation;
        EditText field;
        TextView summary;
        final List<String> types = new ArrayList<>();
        final List<TextView> headers = new ArrayList<>();
        final List<LinearLayout> lists = new ArrayList<>();
        final Runnable render = () -> render();
        boolean current() { return a.mode.equals(MODE) && a.screenGeneration == generation && screen == this; }
    }

    private MasterSearch() { }

    /** Forget loaded lists (after a restore or a playlist change). */
    static void clear() { LISTS.clear(); KEYS.clear(); FAILED.clear(); outOfMemory = false; }

    static void show(MainActivity a) {
        a.mode = MODE;
        a.playerReturn = null;
        a.shell("SEARCH");
        Screen s = new Screen();
        s.a = a;
        s.generation = a.screenGeneration;
        screen = s;

        boolean vod = !a.liveOnly() && hasXtream(a);
        s.types.add("live");
        if (vod) { s.types.add("vod"); s.types.add("series"); }

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(column, new LinearLayout.LayoutParams(-1, -1));

        EditText field = new EditText(a);
        s.field = field;
        field.setSingleLine(true);
        field.setHint(vod ? "⌕  Search channels, movies and series" : "⌕  Search channels");
        field.setTextColor(Theme.TEXT);
        field.setHintTextColor(Theme.HINT);
        field.setTextSize(21);
        field.setPadding(a.dp(18), 0, a.dp(18), 0);
        field.setBackground(a.bg(a.PANEL));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setOnFocusChangeListener((v, f) -> field.setBackground(a.bg(f ? Theme.FOCUS : a.PANEL)));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, a.dp(56));
        flp.setMargins(a.dp(5), a.dp(5), a.dp(5), a.dp(5));
        column.addView(field, flp);

        s.summary = a.label("", 15);
        s.summary.setTextColor(Theme.MUTED);
        s.summary.setPadding(a.dp(8), 0, a.dp(8), 0);
        column.addView(s.summary, new LinearLayout.LayoutParams(-1, a.dp(34)));

        LinearLayout columns = new LinearLayout(a);
        column.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        for (String type : s.types) {
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1);
            clp.setMargins(a.dp(4), 0, a.dp(4), 0);
            columns.addView(col, clp);
            TextView h = a.label(TITLES[indexOf(type)], 20);
            h.setTypeface(null, Typeface.BOLD);
            h.setTextColor(a.BRASS);
            h.setPadding(a.dp(10), 0, a.dp(10), 0);
            col.addView(h, new LinearLayout.LayoutParams(-1, a.dp(40)));
            ScrollView sv = new ScrollView(a);
            LinearLayout list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            sv.addView(list);
            col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
            s.headers.add(h);
            s.lists.add(list);
        }

        field.setText(query);
        field.setSelection(query.length());
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int x, int y, int z) { }
            public void onTextChanged(CharSequence c, int x, int y, int z) { }
            public void afterTextChanged(Editable e) { query = e.toString(); UI.removeCallbacks(s.render); UI.postDelayed(s.render, 300); }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            UI.removeCallbacks(s.render);
            render();
            ((InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(), 0);
            for (LinearLayout l : s.lists) if (l.getChildCount() > 0) { l.getChildAt(0).requestFocus(); break; }
            return true;
        });

        // Nothing loads until something is typed (render starts the loading then).
        render();
        if (!focusResume(s)) field.requestFocus();
    }

    // ------------------------------------------------------------------ loading

    private static boolean hasXtream(MainActivity a) {
        JSONArray pls = a.library.inUse();
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p != null && !Library.M3U.equals(p.optString("type"))) return true;
        }
        return false;
    }

    /** Queues whatever list of a saved playlist this screen needs and doesn't have yet: Live TV first, active playlist first. */
    private static void load(MainActivity a) {
        Screen s = screen;
        if (s == null || outOfMemory) return;
        for (String type : s.types)
            for (JSONObject p : playlists(a)) {
                if (Library.M3U.equals(p.optString("type")) && !type.equals("live")) continue;
                String key = p.optString("id") + "|" + type;
                if (LISTS.containsKey(key) || !LOADING.add(key)) continue;
                synchronized (QUEUE) { QUEUE.add(new Object[]{p, type, key}); }
            }
        synchronized (QUEUE) {
            if (working || QUEUE.isEmpty()) return;
            working = true;
        }
        new Thread(() -> {
            while (true) {
                Object[] job;
                synchronized (QUEUE) { job = QUEUE.poll(); if (job == null) { working = false; return; } }
                loadOne(a, (JSONObject) job[0], (String) job[1], (String) job[2]);
            }
        }, "search-lists").start();
    }

    /** Loads one list on the loader thread and hands it to the UI thread. Never throws, even when memory runs out. */
    private static void loadOne(MainActivity a, JSONObject p, String type, String key) {
        JSONArray got;
        String[] keys;
        boolean ok = true, oom = false;
        try {
            got = type.equals("live") ? a.library.liveChannelsOf(p) : a.library.vodOf(p, type);
            keys = new String[got.length()];
            for (int k = 0; k < keys.length; k++) {
                JSONObject it = got.optJSONObject(k);
                keys[k] = it == null ? "" : XtreamProtocol.searchKey(it.optString("name"));
            }
        } catch (OutOfMemoryError e) {
            got = new JSONArray(); keys = new String[0]; ok = false; oom = true;
        } catch (Throwable e) {
            got = new JSONArray(); keys = new String[0]; ok = false;
        }
        final JSONArray list = got;
        final String[] names = keys;
        final boolean loaded = ok, noMemory = oom;
        a.runOnUiThread(() -> {
            LOADING.remove(key);
            LISTS.put(key, list);
            KEYS.put(key, names);
            if (loaded) FAILED.remove(key); else FAILED.add(key);
            if (noMemory) {
                // Stop here: drop what's queued so the app keeps running with the lists it has.
                outOfMemory = true;
                synchronized (QUEUE) {
                    for (Object[] j : QUEUE) { LOADING.remove((String) j[2]); FAILED.add((String) j[2]); }
                    QUEUE.clear();
                }
            }
            Screen now = screen;
            if (now == null || !now.current()) return;
            View f = a.getCurrentFocus();
            boolean inResults = false;
            for (LinearLayout l : now.lists) if (f != null && isInside(f, l)) inResults = true;
            if (inResults) updateSummary(-1); else render();
        });
    }

    private static boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    // ------------------------------------------------------------------ results

    /** The saved playlists, the active one first. */
    private static List<JSONObject> playlists(MainActivity a) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray pls = a.library.inUse();
        String active = a.library.activeId();
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < pls.length(); i++) {
                JSONObject p = pls.optJSONObject(i);
                if (p != null && (pass == 0) == p.optString("id").equals(active)) out.add(p);
            }
        return out;
    }

    private static int lastTotal;

    private static void render() {
        Screen s = screen;
        if (s == null || !s.current()) return;
        MainActivity a = s.a;
        String qk = XtreamProtocol.searchKey(query);
        boolean searching = qk.length() >= 2;
        if (searching) load(a);
        int total = 0;
        for (int c = 0; c < s.types.size(); c++) {
            String type = s.types.get(c);
            LinearLayout list = s.lists.get(c);
            list.removeAllViews();
            if (!searching) { s.headers.get(c).setText(TITLES[indexOf(type)]); continue; }
            // Names starting with the query come first, then names containing every word, from every playlist.
            List<JSONObject> starts = new ArrayList<>(), contains = new ArrayList<>();
            for (JSONObject p : playlists(a)) {
                String key = p.optString("id") + "|" + type;
                JSONArray all = LISTS.get(key);
                String[] keys = KEYS.get(key);
                if (all == null || keys == null) continue;
                for (int i = 0; i < all.length() && i < keys.length; i++) {
                    int rank = XtreamProtocol.searchRank(keys[i], qk);
                    if (rank < 0) continue;
                    JSONObject it = all.optJSONObject(i);
                    if (it != null) (rank == 0 ? starts : contains).add(it);
                }
            }
            starts.addAll(contains);
            total += starts.size();
            s.headers.get(c).setText(TITLES[indexOf(type)] + "  (" + starts.size() + ")");
            JSONArray shown = new JSONArray();
            for (int n = 0; n < Math.min(LIMIT, starts.size()); n++) shown.put(starts.get(n));
            for (int n = 0; n < shown.length(); n++) row(a, type, list, shown.optJSONObject(n), shown);
            if (starts.size() > LIMIT) note(a, list, "First " + LIMIT + " of " + starts.size() + ". Keep typing to narrow it down.");
            else if (starts.isEmpty()) note(a, list, "No matches");
        }
        updateSummary(searching ? total : -1);
    }

    private static void updateSummary(int total) {
        Screen s = screen;
        if (s == null) return;
        MainActivity a = s.a;
        if (total >= 0) lastTotal = total;
        boolean searching = XtreamProtocol.searchKey(query).length() >= 2;
        String text;
        if (!searching) {
            int ch = 0, mv = 0, se = 0;
            for (Map.Entry<String, JSONArray> e : LISTS.entrySet()) {
                if (e.getKey().endsWith("|live")) ch += e.getValue().length();
                else if (e.getKey().endsWith("|vod")) mv += e.getValue().length();
                else se += e.getValue().length();
            }
            int pls = a.library.inUse().length();
            if (LISTS.isEmpty() && LOADING.isEmpty()) text = "Type at least 2 letters, then press Search. Your " + (s.types.size() > 1 ? "channels, movies and series" : "channels") + " load after you type.";
            else text = "Type at least 2 letters to search " + ch + " channels" + (s.types.size() > 1 ? ", " + mv + " movies and " + se + " series" : "")
                    + (pls > 1 ? " in " + pls + " playlists" : "") + ".";
        } else {
            text = lastTotal == 0 ? "Nothing matches \"" + query.trim() + "\"." : lastTotal + (lastTotal == 1 ? " match" : " matches") + "  •  ★ = in Favorites  •  ☰ = in My List  •  OK to open";
        }
        if (!LOADING.isEmpty()) text += "  •  still loading " + LOADING.size() + (LOADING.size() == 1 ? " list…" : " lists…");
        if (outOfMemory) text += "  •  not enough memory for every list, so only the loaded ones are searched";
        else if (!FAILED.isEmpty()) text += "  •  " + FAILED.size() + (FAILED.size() == 1 ? " list" : " lists") + " couldn't load";
        s.summary.setText(text);
    }

    private static void row(MainActivity a, String type, LinearLayout list, JSONObject item, JSONArray shown) {
        boolean live = type.equals("live"), series = type.equals("series");
        String id = item.optString(series ? "series_id" : "stream_id");
        String title = item.optString("name", "Untitled");
        String tag = type + "|" + item.optString("playlist") + "|" + id;
        TextView[] ref = new TextView[1];
        ref[0] = a.button(title, list, () -> {
            resumeTag = tag;
            if (live) openLive(a, item, ref[0], shown); else openVod(a, type, item, shown);
        });
        TextView r = ref[0];
        r.setTag(tag);
        r.setTextSize(18);
        r.setSingleLine(true);
        r.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (live) {
            ChannelIcons.attach(r, item.optString("stream_icon"), title, 28);
            if (a.fromActive(item)) a.styleSearchRow(r, id, title + a.playlistTag(item)); else a.styleOtherSearchRow(r, item);
        } else {
            JSONObject pl = a.library.find(item.optString("playlist"));
            boolean mine = pl != null && a.inMyListIn(Library.prefixOf(pl), type, id);
            r.setText((mine ? "☰ " : "") + title + a.playlistTag(item));
        }
    }

    private static void openLive(MainActivity a, JSONObject item, TextView row, JSONArray shown) {
        a.searchHits = shown;
        a.searchAction(item, row);
    }

    private static void openVod(MainActivity a, String type, JSONObject item, JSONArray shown) {
        String pid = item.optString("playlist");
        if (!pid.isEmpty() && !pid.equals(a.library.activeId())) {
            JSONObject p = a.library.find(pid);
            if (p == null) { Menus.alert(a, "This title's playlist was removed."); return; }
            a.switchPlaylist(p);
        }
        JSONArray same = new JSONArray();
        for (int i = 0; i < shown.length(); i++) {
            JSONObject o = shown.optJSONObject(i);
            if (o != null && o.optString("playlist").equals(pid)) same.put(o);
        }
        Details.open(a, type, item, "", same, () -> show(a));
    }

    private static boolean focusResume(Screen s) {
        String tag = resumeTag;
        resumeTag = null;
        if (tag == null) return false;
        for (LinearLayout l : s.lists)
            for (int i = 0; i < l.getChildCount(); i++) {
                View v = l.getChildAt(i);
                if (tag.equals(v.getTag())) { v.requestFocus(); return true; }
            }
        return false;
    }

    private static void note(MainActivity a, LinearLayout box, String text) {
        TextView t = a.label(text, 15);
        t.setTextColor(Theme.MUTED);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private static int indexOf(String type) {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(type)) return i;
        return 0;
    }
}
