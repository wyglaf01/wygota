package com.wygtv.tv;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;


/**
 * Movies / Series → Search, working like Live TV search: a search bar at the top of the screen, beside the MOVIES /
 * SERIES title, with a ⌕ Search button. Nothing loads or searches while typing; press Search (or the keyboard's search
 * key) once the name is typed. Every switched-on playlist is searched, one at a time, by reading its saved list item by
 * item and keeping only the matches (Library.searchVodIn), so a huge list never has to fit in memory. Results show as
 * posters as each playlist finishes and are kept for coming back from a details page. OK opens the details page;
 * holding OK adds a title to My List.
 */
final class VodSearch {
    static final String CATEGORY = "search";
    static final int MAX = 200;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** Per section ("vod" / "series"): what's typed, the last search's text, its results and its summary line. */
    private static final java.util.Map<String, String> QUERIES = new java.util.HashMap<>(), SEARCHED = new java.util.HashMap<>(), NOTES = new java.util.HashMap<>();
    private static final java.util.Map<String, JSONArray> RESULTS = new java.util.HashMap<>();
    /** The current search run; a newer one stops older ones. */
    private static int run;

    private VodSearch() { }

    static void show(MainActivity a) {
        String type = a.mode;
        boolean series = type.equals("series");
        a.listGeneration++;
        a.category = CATEGORY;
        a.detail.removeAllViews();
        final int list = a.listGeneration;

        LinearLayout bar = headerSlot(a);
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setHint(series ? "⌕  Type a series name" : "⌕  Type a movie name");
        field.setTextColor(Theme.TEXT);
        field.setHintTextColor(Theme.HINT);
        field.setTextSize(20);
        field.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10));
        field.setBackground(a.bg(a.PANEL));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTag(NavRail.TOP_TARGET); // Right from the top of the side menu lands on the bar.
        field.setOnFocusChangeListener((v, f) -> field.setBackground(a.bg(f ? Theme.FOCUS : a.PANEL)));
        bar.addView(field, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        a.detail.addView(column, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView summary = a.label("", 15);
        summary.setTextColor(Theme.MUTED);
        summary.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        summary.setPadding(a.dp(12), 0, a.dp(12), 0);
        column.addView(summary, new LinearLayout.LayoutParams(-1, a.dp(34)));
        LinearLayout results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        column.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
        Screen s = new Screen(a, type, list, field, summary, results);

        Runnable go = () -> {
            ((InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(), 0);
            search(s);
        };
        TextView button = a.button("⌕ Search", bar, go);
        button.setTextSize(17);
        button.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(a.dp(150), -2);
        blp.setMargins(a.dp(10), 0, a.dp(5), 0);
        button.setLayoutParams(blp);
        // The bar sits in the title row; take it away again as soon as the results leave the screen (another category, My List…).
        column.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { if (field.getParent() == bar) { bar.removeView(field); bar.removeView(button); } }
        });

        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence t, int b, int c, int d) { }
            public void onTextChanged(CharSequence t, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { QUERIES.put(type, e.toString()); typed(s); }
        });
        field.setOnEditorActionListener((v, action, event) -> { go.run(); return true; });

        String last = QUERIES.containsKey(type) ? QUERIES.get(type) : "";
        field.setText(last);
        field.setSelection(last.length());
        JSONArray kept = RESULTS.get(type);
        if (!last.isEmpty() && last.equals(SEARCHED.get(type)) && kept != null && kept.length() > 0) {
            // Coming back (from a details page, or to this category): the last results again, nothing searched again.
            render(s, true);
            return;
        }
        field.requestFocus();
        typed(s);
    }

    /** One Search screen on show. */
    private static final class Screen {
        final MainActivity a; final String type; final int list; final EditText field; final TextView summary; final LinearLayout results;
        Screen(MainActivity a, String type, int list, EditText field, TextView summary, LinearLayout results) {
            this.a = a; this.type = type; this.list = list; this.field = field; this.summary = summary; this.results = results;
        }
        boolean current() { return list == a.listGeneration && CATEGORY.equals(a.category) && type.equals(a.mode) && !a.isFinishing() && !a.isDestroyed(); }
        String noun(int n) { return type.equals("series") ? "series" : n == 1 ? "movie" : "movies"; }
    }

    /** While typing: nothing is searched or loaded yet, just a hint. */
    private static void typed(Screen s) {
        if (!s.current()) return;
        String q = QUERIES.containsKey(s.type) ? QUERIES.get(s.type) : "";
        int pls = xtream(s.a).size();
        if (q.equals(SEARCHED.get(s.type))) { s.summary.setText(NOTES.containsKey(s.type) ? NOTES.get(s.type) : ""); return; }
        s.summary.setText(XtreamProtocol.searchKey(q).isEmpty()
                ? "Type a " + (s.type.equals("series") ? "series" : "movie") + " name, then press Search (or the search key on the keyboard)." + (pls > 1 ? " All " + pls + " playlists are searched." : "")
                : "Press Search to look for \"" + q.trim() + "\"" + (pls > 1 ? " in " + pls + " playlists" : "") + ".");
    }

    /** The switched-on Xtream playlists (the ones with movies and series), the playing one first. */
    private static List<JSONObject> xtream(MainActivity a) {
        List<JSONObject> order = new ArrayList<>();
        String activeId = a.library.activeId();
        JSONObject act = a.library.find(activeId);
        JSONArray pls = a.library.inUse();
        if (act != null && !Library.M3U.equals(act.optString("type"))) order.add(act);
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p != null && !p.optString("id").equals(activeId) && !Library.M3U.equals(p.optString("type"))) order.add(p);
        }
        return order;
    }

    /**
     * Searches every playlist for the typed name, one at a time on a background thread. The first search downloads each
     * playlist's list to a file (kept for 6 hours); results appear as each playlist finishes.
     */
    private static void search(Screen s) {
        if (!s.current()) return;
        String typedText = QUERIES.containsKey(s.type) ? QUERIES.get(s.type) : "";
        String q = XtreamProtocol.searchKey(typedText);
        if (q.length() < 2) { s.summary.setText("Type at least 2 letters of the name, then press Search."); return; }
        List<JSONObject> order = xtream(s.a);
        if (order.isEmpty()) { s.summary.setText("None of your switched-on playlists has " + (s.type.equals("series") ? "series." : "movies.")); return; }
        final int mine = ++run;
        final String type = s.type;
        final JSONArray found = new JSONArray();
        RESULTS.put(type, found);
        SEARCHED.put(type, typedText);
        NOTES.put(type, "");
        s.results.removeAllViews();
        s.summary.setText("Searching…");
        new Thread(() -> {
            List<String> problems = new ArrayList<>();
            boolean capped = false;
            for (int i = 0; i < order.size(); i++) {
                JSONObject p = order.get(i);
                String name = Library.nameOf(p);
                final int n = i;
                UI.post(() -> { if (mine == run && s.current()) s.summary.setText("Searching " + name + (order.size() > 1 ? " (" + (n + 1) + " of " + order.size() + ")" : "") + "…  " + found.length() + " found so far"); });
                JSONArray got = null;
                try { got = s.a.library.searchVodIn(p, type, q, MAX - found.length()); }
                catch (OutOfMemoryError e) { System.gc(); problems.add(name + " (not enough memory)"); }
                catch (Throwable e) { problems.add(name); }
                if (mine != run) return;
                if (got == null || got.length() == 0) continue;
                final JSONArray add = got;
                UI.post(() -> {
                    if (mine != run) return;
                    for (int k = 0; k < add.length() && found.length() < MAX; k++) found.put(add.opt(k));
                    if (s.current()) render(s, false);
                });
                if (found.length() + got.length() >= MAX) { capped = true; break; }
            }
            final boolean full = capped;
            UI.post(() -> {
                if (mine != run) return;
                int c = found.length();
                String t = typedText.trim();
                NOTES.put(type, (c == 0 ? "No " + (type.equals("series") ? "series" : "movies") + " match \"" + t + "\""
                        : c + " " + s.noun(c) + " match \"" + t + "\"" + (full || c >= MAX ? " (showing the first " + MAX + ", type more of the name to narrow it down)" : "") + "  •  hold OK to add to My List")
                        + (problems.isEmpty() ? "" : "  •  couldn't search: " + android.text.TextUtils.join(", ", problems)));
                if (!s.current()) return;
                s.summary.setText(NOTES.get(type));
                if (c > 0 && !s.results.hasFocus()) { View first = firstPoster(s.results); if (first != null) first.requestFocus(); }
            });
        }, "vod-search").start();
    }

    /** Shows the current results as posters, keeping the highlight on the same poster when it's already in them. */
    private static void render(Screen s, boolean takeFocus) {
        JSONArray found = RESULTS.get(s.type);
        if (found == null) found = new JSONArray();
        View focus = s.a.getCurrentFocus();
        boolean inResults = focus != null && s.results.hasFocus();
        if (inResults && focus.getTag() instanceof String) s.a.resumeStreamId = (String) focus.getTag();
        s.a.items = found;
        s.results.removeAllViews();
        if (found.length() == 0) return;
        PosterGrid.showIn(s.a, s.results, found, takeFocus || inResults);
        if (NOTES.containsKey(s.type) && !NOTES.get(s.type).isEmpty() && s.a.resumeStreamId == null) s.summary.setText(NOTES.get(s.type));
    }

    private static View firstPoster(LinearLayout results) {
        java.util.ArrayList<View> all = new java.util.ArrayList<>();
        results.addFocusables(all, View.FOCUS_DOWN);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * The row at the very top of the screen holding the screen title ("MOVIES" / "SERIES"): the search bar goes there,
     * to the right of the title and lined up with it. The title is wrapped in that row the first time; the row is
     * emptied of any older search bar.
     */
    private static LinearLayout headerSlot(MainActivity a) {
        View top = a.root.getChildAt(0);
        LinearLayout row;
        if (top instanceof LinearLayout && HEADER.equals(top.getTag())) {
            row = (LinearLayout) top;
            while (row.getChildCount() > 1) row.removeViewAt(1);
        } else {
            ViewGroup.LayoutParams lp = top.getLayoutParams();
            a.root.removeViewAt(0);
            row = new LinearLayout(a);
            row.setTag(HEADER);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(top, new LinearLayout.LayoutParams(-2, -1));
            a.root.addView(row, 0, lp);
        }
        LinearLayout slot = new LinearLayout(a);
        slot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
        sp.leftMargin = a.dp(28);
        row.addView(slot, sp);
        return slot;
    }

    private static final String HEADER = "vodsearch_header";

}
