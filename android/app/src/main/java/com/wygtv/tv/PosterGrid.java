package com.wygtv.tv;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Movies and Series as a poster grid: each tile has the poster, title, year and rating (series: where you
 * stopped). Above the grid an info panel shows the focused title's details: year, runtime, rating, genres and plot,
 * first from the list, then from the provider's get_vod_info / get_series_info, then TMDb when a key is set.
 */
final class PosterGrid {
    static final int PAGE = 60, COLUMNS = 5;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    /** Details already fetched this session, by playlist + type + id. */
    private static final Map<String, JSONObject> META = new HashMap<>();

    private PosterGrid() { }

    static void show(MainActivity a, int ignoredOffset) {
        a.detail.removeAllViews();
        showIn(a, a.detail, a.items, true);
    }

    /** The info panel and poster grid for these items, added to box (the right-hand pane, or under the search bar). */
    static void showIn(MainActivity a, LinearLayout box, JSONArray all, boolean takeFocus) {
        boolean series = a.mode.equals("series");
        String type = a.mode, key = series ? "series_id" : "stream_id";
        // Settings > Watched series: Hide leaves out every series whose episodes are all watched.
        int hiddenWatched = 0;
        JSONArray keep = all;
        if (series && Watch.hideWatched(a)) {
            keep = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject it = all.optJSONObject(i);
                if (it != null && Watch.complete(a, it.optString(key))) hiddenWatched++;
                else keep.put(it);
            }
        }
        final JSONArray items = keep;
        // Posters are added in chunks as you scroll down, so a big category opens fast and scrolls to the end.
        int want = PAGE;
        if (a.resumeStreamId != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && a.resumeStreamId.equals(it.optString(key))) { want = (i / PAGE + 1) * PAGE; break; }
            }
        }
        final int generation = a.screenGeneration, list = a.listGeneration;

        // Info panel for the focused title.
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(a.dp(10), 0, a.dp(10), a.dp(6));
        box.addView(info, new LinearLayout.LayoutParams(-1, a.dp(150)));
        TextView title = line(a, info, 24, Theme.TEXT, 1);
        title.setTypeface(null, Typeface.BOLD);
        TextView facts = line(a, info, 15, Theme.ACCENT, 1);
        TextView plot = line(a, info, 15, Theme.MUTED, 4);
        title.setText(items.length() == 0 ? "Nothing here yet" : series ? "Series" : "Movies");
        facts.setText((items.length() == 0 ? "" : items.length() + (series ? " series" : " movies") + "  •  scroll with the arrows, press OK on a poster for details")
                + (hiddenWatched > 0 ? (items.length() == 0 ? "" : "  •  ") + hiddenWatched + " watched series hidden (Settings > Appearance)" : ""));

        ScrollView sv = new ScrollView(a);
        sv.setSmoothScrollingEnabled(true);
        box.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, a.dp(4), 0, a.dp(12));
        sv.addView(grid);

        Runnable[] pending = new Runnable[1];
        String[] focused = new String[1];
        int[] shown = {0};
        Runnable[] more = new Runnable[1];
        View[] resumeTile = new View[1];
        more[0] = () -> {
            int from = shown[0], to = Math.min(items.length(), from + PAGE);
            LinearLayout row = null;
            for (int i = from; i < to; i++) {
                if (i % COLUMNS == 0) {
                    row = new LinearLayout(a);
                    grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
                } else if (row == null) row = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
                JSONObject item = items.optJSONObject(i);
                if (item == null) item = new JSONObject();
                final JSONObject it = item;
                final int index = i;
                String id = it.optString(key);
                View tile = tile(a, row, type, it, id, generation);
                tile.setOnFocusChangeListener((v, f) -> {
                    v.setScaleX(f ? 1.07f : 1f);
                    v.setScaleY(f ? 1.07f : 1f);
                    v.setBackground(a.bg(f ? Theme.FOCUS : a.PANEL));
                    v.setElevation(f ? a.dp(6) : 0);
                    if (!f) return;
                    focused[0] = id;
                    if (index >= shown[0] - COLUMNS * 2 && shown[0] < items.length() && list == a.listGeneration) more[0].run();
                    if (pending[0] != null) UI.removeCallbacks(pending[0]);
                    pending[0] = () -> describe(a, type, it, id, title, facts, plot, focused, generation, list);
                    UI.postDelayed(pending[0], 220);
                });
                if (a.resumeStreamId != null && a.resumeStreamId.equals(id)) resumeTile[0] = tile;
            }
            shown[0] = to;
            // Keep the last row's tiles the same width as the rest (spacers are removed when more posters come).
            if (to < items.length() || row == null) return;
            for (int k = row.getChildCount(); k < COLUMNS; k++) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        };
        while (shown[0] < Math.min(want, items.length())) more[0].run();
        a.resumeStreamId = null;
        View first = grid.getChildCount() > 0 ? ((LinearLayout) grid.getChildAt(0)).getChildAt(0) : null;
        if (resumeTile[0] != null) resumeTile[0].requestFocus();
        else if (!takeFocus) return;
        else if (first != null) first.requestFocus();
        else if (a.side.getChildCount() > a.sideFixed) a.side.getChildAt(a.sideFixed).requestFocus();
    }

    private static View tile(MainActivity a, LinearLayout row, String type, JSONObject item, String id, int generation) {
        boolean series = type.equals("series");
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(6));
        t.setBackground(a.bg(a.PANEL));
        t.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(a.dp(5), a.dp(6), a.dp(5), a.dp(6));
        row.addView(t, lp);

        JSONObject m = Tmdb.fromProvider(item, null);
        String name = m.optString("title", item.optString("name", "Untitled"));
        FrameLayout art = new FrameLayout(a);
        t.addView(art, new LinearLayout.LayoutParams(-1, a.dp(168)));
        TextView placeholder = a.label(name, 15);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTextColor(Theme.MUTED);
        placeholder.setPadding(a.dp(6), 0, a.dp(6), 0);
        art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        Details.image(poster, m.optString("poster"), a.dp(140), generation, a);

        JSONObject home = otherPlaylist(a, item);
        String pre = home == null ? a.accountPrefix() : Library.prefixOf(home);
        boolean mine = a.inMyListIn(pre, type, id), watched = series && Watch.complete(a, id);
        TextView label = a.label((mine ? "☰ " : "") + (watched ? "✓ " : "") + name, 14);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(a.dp(2), a.dp(5), a.dp(2), 0);
        t.addView(label, new LinearLayout.LayoutParams(-1, -2));
        String sub = series ? Watch.summary(Watch.load(a, id)) : "";
        if (sub.isEmpty()) {
            StringBuilder b = new StringBuilder();
            if (!m.optString("year").isEmpty()) b.append(m.optString("year"));
            if (!m.optString("rating").isEmpty()) b.append(b.length() > 0 ? "   ★ " : "★ ").append(m.optString("rating"));
            sub = b.toString();
        } else if (!sub.startsWith("✓")) sub = "▶ " + sub;
        // Search results from several playlists say which playlist each one is in.
        if (!item.optString("playlist").isEmpty() && a.library.inUse().length() > 1) {
            String from = Library.nameOf(a.library.find(item.optString("playlist")));
            sub = sub.isEmpty() ? from : sub + "  ·  " + from;
        }
        TextView under = a.label(sub.isEmpty() ? " " : sub, 12);
        under.setTextColor(Theme.MUTED);
        under.setSingleLine(true);
        under.setPadding(a.dp(2), 0, a.dp(2), 0);
        t.addView(under, new LinearLayout.LayoutParams(-1, -2));

        t.setTag(id);
        t.setOnClickListener(v -> {
            if (home == null) { a.select(item); return; }
            // A Search result from another playlist: switch to that playlist first, keeping the results to come back to.
            JSONArray same = new JSONArray(), shown = a.items;
            String pid = item.optString("playlist"), cat = a.category;
            for (int i = 0; shown != null && i < shown.length(); i++) {
                JSONObject o = shown.optJSONObject(i);
                if (o != null && o.optString("playlist").equals(pid)) same.put(o);
            }
            a.switchPlaylist(home);
            a.category = cat;
            a.items = shown;
            Details.open(a, type, item, cat, same);
        });
        // Hold OK (or Menu): add to / remove from My List without opening the details.
        t.setOnLongClickListener(v -> {
            boolean added = a.toggleMyListIn(pre, type, item);
            Toast.makeText(a, (added ? "Added to My List: " : "Removed from My List: ") + name, Toast.LENGTH_SHORT).show();
            if (!added && "mylist".equals(a.category)) a.showMyList();
            return true;
        });
        return t;
    }

    /** Fills the info panel for the focused title: list data now, provider and TMDb details as they arrive. */
    private static void describe(MainActivity a, String type, JSONObject item, String id, TextView title, TextView facts, TextView plot,
                                 String[] focused, int generation, int list) {
        boolean series = type.equals("series");
        JSONObject home = otherPlaylist(a, item);
        String cacheKey = (home == null ? a.accountPrefix() : Library.prefixOf(home)) + type + id;
        Runnable[] render = new Runnable[1];
        JSONObject[] meta = {META.containsKey(cacheKey) ? META.get(cacheKey) : Tmdb.fromProvider(item, null)};
        render[0] = () -> {
            if (generation != a.screenGeneration || list != a.listGeneration || !id.equals(focused[0])) return;
            JSONObject m = meta[0];
            title.setText(m.optString("title", item.optString("name")));
            String f = Details.facts(m, series), g = m.optString("genres");
            if (series) { String w = Watch.summary(Watch.load(a, id)); if (!w.isEmpty()) f = f + (f.isEmpty() ? "" : "   •   ") + "▶ " + w; }
            facts.setText(g.isEmpty() ? f : f.isEmpty() ? g : f + "   •   " + g);
            String p = m.optString("plot");
            plot.setText(p.isEmpty() ? (m.has("source") || m.has("fetched") ? "No description." : "") : p);
        };
        render[0].run();
        // Details come from the playing playlist's server, so a title from another playlist shows its list data here.
        if (META.containsKey(cacheKey) || a.library.activeIsM3u() || home != null) return;
        a.apiQuiet((series ? "get_series_info&series_id=" : "get_vod_info&vod_id=") + a.enc(id), answer -> {
            JSONObject provider = Tmdb.fromProvider(item, answer);
            try { provider.put("fetched", true); } catch (Exception ignored) { }
            meta[0] = provider;
            if (!Tmdb.enabled(a)) { META.put(cacheKey, provider); render[0].run(); return; }
            render[0].run();
            POOL.execute(() -> {
                JSONObject t = Tmdb.enrich(a, type, provider, item.optString("name"));
                UI.post(() -> {
                    meta[0] = t == null ? provider : t;
                    META.put(cacheKey, meta[0]);
                    render[0].run();
                });
            });
        });
    }

    /** The playlist a Search result comes from when it isn't the playing one; null otherwise (or when it was removed). */
    static JSONObject otherPlaylist(MainActivity a, JSONObject item) {
        String pid = item.optString("playlist");
        if (pid.isEmpty() || pid.equals(a.library.activeId())) return null;
        return a.library.find(pid);
    }

    private static TextView line(MainActivity a, LinearLayout parent, int size, int color, int lines) {
        TextView t = a.label("", size);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, a.dp(2), 0, a.dp(2));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

}
