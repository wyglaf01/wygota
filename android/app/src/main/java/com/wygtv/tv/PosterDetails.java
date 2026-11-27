package com.wygtv.tv;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The details page of a poster on the main screen (option 1), laid out like the Movies and Series details page.
 * It opens straight away with what the popular list knows (and TMDb / OMDb when a key is set), then looks for the
 * title in every switched-on playlist in the background, one playlist at a time. When it's found, ▶ Play (movies)
 * or ☰ Episodes (series) plays it from that playlist; with several copies (other playlists, HD / 4K) it asks which.
 */
final class PosterDetails {
    private PosterDetails() { }

    /** What each title's search found, so coming back from the player doesn't search again. */
    private static final Map<String, Found> FOUND = new HashMap<>();

    private static final class Found {
        final List<JSONObject> copies = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
    }

    /** Forgets earlier searches (playlists changed). */
    static void forget() { FOUND.clear(); }

    private static String keyOf(String type, JSONObject poster) {
        return type + "|" + poster.optString("_title") + "|" + poster.optString("_year");
    }

    static void open(MainActivity a, String type, JSONObject poster) {
        boolean series = type.equals("series");
        String name = poster.optString("_title", poster.optString("name", "Untitled"));
        Runnable reopen = () -> open(a, type, poster);

        a.detailsType = type;
        a.mode = "details";
        a.detailsBack = a::home;
        a.shell(series ? "SERIES" : "MOVIES");
        final int generation = a.screenGeneration;

        FrameLayout stage = new FrameLayout(a);
        a.body.addView(stage, new LinearLayout.LayoutParams(0, -1, 1));
        ImageView backdrop = new ImageView(a);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(.28f);
        stage.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(a);
        row.setPadding(a.dp(12), a.dp(4), a.dp(12), 0);
        stage.addView(row, new FrameLayout.LayoutParams(-1, -1));
        ImageView posterView = new ImageView(a);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterView.setBackground(a.bg(a.PANEL));
        posterView.setClipToOutline(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(a.dp(220), a.dp(330));
        plp.setMargins(0, a.dp(6), a.dp(28), 0);
        row.addView(posterView, plp);

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        row.addView(sv, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);

        TextView title = Details.text(a, col, 32, Theme.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        TextView facts = Details.text(a, col, 17, Theme.MUTED);
        TextView genres = Details.text(a, col, 16, Theme.ACCENT);

        LinearLayout buttons = new LinearLayout(a);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMargins(0, a.dp(10), 0, a.dp(8));
        col.addView(buttons, blp);
        Found[] found = {FOUND.get(keyOf(type, poster))};
        TextView play = Details.action(a, buttons, "⌕  Looking in your playlists…", () -> {
            Found f = found[0];
            if (f == null) { Toast.makeText(a, "Still looking for it in your playlists…", Toast.LENGTH_SHORT).show(); return; }
            if (f.copies.isEmpty()) { notFound(a, name, f, () -> { FOUND.remove(keyOf(type, poster)); reopen.run(); }); return; }
            if (f.copies.size() == 1) { start(a, type, f.copies.get(0), reopen); return; }
            List<String> labels = new ArrayList<>();
            for (JSONObject o : f.copies)
                labels.add(Library.nameOf(a.library.find(o.optString("playlist"))) + "  •  " + o.optString("name", name));
            new Dialogs.Builder(a).setTitle(name + "  •  pick a copy")
                    .setItems(labels.toArray(new String[0]), (d, w) -> start(a, type, f.copies.get(w), reopen))
                    .setNegativeButton("Cancel", null).show();
        });
        Details.action(a, buttons, "▷  Trailer", () -> Showcase.trailer(a, series, poster));
        if (!Tmdb.key(a).isEmpty()) Details.action(a, buttons, "◎  Where to watch", () -> Showcase.whereToWatch(a, series, poster));

        TextView found1 = Details.text(a, col, 16, Theme.ACCENT);
        TextView plot = Details.text(a, col, 19, Theme.TEXT);
        plot.setLineSpacing(0, 1.12f);
        plot.setFocusable(true);
        plot.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        plot.setOnFocusChangeListener((v, f) -> plot.setBackgroundColor(f ? Theme.alpha(Theme.FOCUS, 0x40) : 0));
        TextView director = Details.text(a, col, 17, Theme.TEXT);
        TextView cast = Details.text(a, col, 17, Theme.TEXT);
        TextView source = Details.text(a, col, 13, Theme.HINT);
        source.setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(10));

        // What the popular list knows, in the details page's own fields.
        JSONObject base = new JSONObject();
        try {
            base.put("title", name).put("year", poster.optString("_year")).put("plot", poster.optString("_overview"))
                    .put("poster", poster.optString("_poster")).put("backdrop", poster.optString("_backdrop"))
                    .put("tmdb_id", poster.optString("_tmdb"));
        } catch (org.json.JSONException ignored) { }
        JSONObject[] shown = {base};
        Runnable render = () -> {
            JSONObject m = shown[0];
            boolean extra = m != base;
            title.setText(m.optString("title", name));
            String f = extra ? Details.facts(m, series) : "";
            if (f.isEmpty()) {
                List<String> parts = new ArrayList<>();
                parts.add(series ? "Series" : "Movie");
                if (!poster.optString("_year").isEmpty()) parts.add(poster.optString("_year"));
                if (!poster.optString("_rating").isEmpty()) parts.add(poster.optString("_rating"));
                f = TextUtils.join("   •   ", parts);
            }
            facts.setText(f);
            Details.show(genres, m.optString("genres"));
            Details.show(plot, m.optString("plot"));
            plot.setFocusable(!m.optString("plot").isEmpty());
            Details.show(director, m.optString("director").isEmpty() ? "" : m.optString("director_label", "Director") + ":  " + m.optString("director"));
            Details.show(cast, m.optString("cast").isEmpty() ? "" : "Cast:  " + m.optString("cast"));
            String from = poster.optString("_source").isEmpty() ? "" : "Popular on " + poster.optString("_source") + ".  ";
            source.setText(from + (extra ? Details.sourceLine(a, m) : Tmdb.enabled(a) ? "" : "Add a free TMDb or OMDb API key in Settings for plots, cast and IMDb ratings."));
            String art = m.optString("poster");
            if (!art.isEmpty()) Details.image(posterView, art, a.dp(220), generation, a);
            String wide = m.optString("backdrop");
            if (!wide.isEmpty()) Details.image(backdrop, wide, a.dp(640), generation, a);
        };
        Runnable showFound = () -> {
            Found f = found[0];
            if (f == null) { found1.setText("Searching your switched-on playlists one at a time…"); return; }
            String trouble = f.problems.isEmpty() ? "" : "  (couldn't check " + TextUtils.join(", ", f.problems) + ")";
            if (f.copies.isEmpty()) {
                play.setText("✕  Not in your playlists");
                found1.setText("Not found in your playlists." + trouble + "  Press OK on the button to search again.");
                return;
            }
            String where = Library.nameOf(a.library.find(f.copies.get(0).optString("playlist")));
            if (f.copies.size() == 1) play.setText((series ? "☰  Episodes" : "▶  Play") + "  •  " + where);
            else play.setText((series ? "☰  Episodes" : "▶  Play") + "  •  " + f.copies.size() + " copies");
            List<String> names = new ArrayList<>();
            for (JSONObject o : f.copies) {
                String n = Library.nameOf(a.library.find(o.optString("playlist")));
                if (!names.contains(n)) names.add(n);
            }
            found1.setText("In your playlists:  " + TextUtils.join(", ", names) + trouble);
        };
        render.run();
        showFound.run();
        play.requestFocus();

        if (Tmdb.enabled(a)) {
            new Thread(() -> {
                JSONObject t = null;
                try { t = Tmdb.enrich(a, type, base, name); } catch (Throwable ignored) { }
                final JSONObject got = t;
                a.runOnUiThread(() -> {
                    if (got == null || generation != a.screenGeneration || a.isFinishing() || a.isDestroyed()) return;
                    shown[0] = got;
                    render.run();
                });
            }, "poster-details").start();
        }
        if (found[0] != null) return;
        new Thread(() -> {
            Found f = search(a, type, poster, generation);
            if (f == null) return; // left the page before it finished
            a.runOnUiThread(() -> {
                FOUND.put(keyOf(type, poster), f);
                if (generation != a.screenGeneration || a.isFinishing() || a.isDestroyed()) return;
                found[0] = f;
                showFound.run();
            });
        }, "poster-find").start();
    }

    /**
     * Looks for the title in every switched-on playlist (the active one first), one at a time, reading each
     * playlist's saved movie or series list from a file. Null if the page was left before it finished.
     */
    private static Found search(MainActivity a, String type, JSONObject poster, int generation) {
        Found f = new Found();
        String title = poster.optString("_title");
        String tmdbId = poster.optString("_tmdb"), year = poster.optString("_year");
        String key = XtreamProtocol.searchKey(title), alt = XtreamProtocol.searchKey(poster.optString("_original"));
        if (alt.equals(key)) alt = "";
        JSONArray pls = a.library.inUse();
        String active = a.library.activeId();
        List<JSONObject> order = new ArrayList<>();
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null) continue;
            if (p.optString("id").equals(active)) order.add(0, p); else order.add(p);
        }
        for (JSONObject p : order) {
            if (Library.M3U.equals(p.optString("type"))) continue; // M3U playlists have no movie or series lists
            if (generation != a.screenGeneration) return null;
            try {
                JSONArray got = a.library.findVod(p, type, tmdbId, key, alt, year, 8);
                for (int i = 0; i < got.length(); i++) f.copies.add(got.optJSONObject(i));
            } catch (OutOfMemoryError e) { System.gc(); f.problems.add(Library.nameOf(p)); }
            catch (Throwable e) { f.problems.add(Library.nameOf(p)); }
        }
        return f;
    }

    private static void notFound(MainActivity a, String name, Found f, Runnable again) {
        String trouble = f.problems.isEmpty() ? "" : "\n\nCouldn't check: " + TextUtils.join(", ", f.problems) + ".";
        new Dialogs.Builder(a).setTitle(name)
                .setMessage("It isn't in your switched-on playlists (or it's in a category you've hidden)." + trouble)
                .setPositiveButton("Search again", (d, w) -> again.run())
                .setNegativeButton("Close", null).show();
    }

    /** Plays a found copy (episodes for a series), switching to its playlist first; Back comes back to this page. */
    private static void start(MainActivity a, String type, JSONObject item, Runnable reopen) {
        String pid = item.optString("playlist");
        if (!pid.isEmpty() && !pid.equals(a.library.activeId())) {
            JSONObject p = a.library.find(pid);
            if (p == null) { Menus.alert(a, "This title's playlist was removed."); return; }
            a.switchPlaylist(p);
        }
        if (type.equals("series")) {
            a.episodesBack = reopen;
            Episodes.show(a, item.optString("series_id"), item.optString("name", "Series"));
            return;
        }
        a.playerReturn = reopen;
        JSONArray list = new JSONArray();
        list.put(item);
        a.zapList = list;
        a.play("vod", item.optString("stream_id"), item.optString("container_extension", "mp4"), item.optString("name", "Movie"));
    }
}
