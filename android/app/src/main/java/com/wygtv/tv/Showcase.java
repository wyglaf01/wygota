package com.wygtv.tv;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Main screen option 1 (the default): popular movies and series right now, in rows of posters to scroll through,
 * from public sources that need no key (Rotten Tomatoes, Apple, TVmaze: see PopularSources). The rows don't depend
 * on any playlist. Choosing a poster opens its details page straight away (like Movies and Series), which then
 * looks for the title in your playlists; ▶ Play plays it from there (see {@link PosterDetails}). A row of shortcuts
 * at the top has the classic Home's items (Live TV, TV Guide, Movies, Series, Search, antenna TV, Playlists,
 * Settings, Exit); they're in the side menu too.
 * Option 2 is the classic Home with its tiles; Settings > Appearance > Main screen picks one.
 */
final class Showcase {
    private Showcase() { }

    static final String PREF = "home_style";
    static final String[] LABELS = {"Option 1: Popular movies & series (default)", "Option 2: Classic (Live TV, Movies, Series and Search tiles)"};

    /** Whether the main screen is option 1 (this screen). */
    static boolean on(SharedPreferences p) { return !"classic".equals(p.getString(PREF, "showcase")); }

    static final int PER_ROW = 20;
    /** How long the popular rows are reused before they're looked up again. */
    static final long KEEP_MS = 6L * 60 * 60 * 1000;

    private static boolean loading;

    /** Builds the screen and fills the rows (from the saved rows straight away, refreshed when they're old). */
    static void show(MainActivity a) {
        a.mode = "home";
        a.screenGeneration++;
        a.listGeneration++;
        final int generation = a.screenGeneration;
        FrameLayout scene = new FrameLayout(a);
        View frame = NavRail.wrap(a, scene, "home");
        frame.setBackground(new android.graphics.drawable.ColorDrawable(0xff111111));
        a.setContentView(frame);

        // The highlighted title's picture fills the back of the screen.
        ImageView backdrop = new ImageView(a);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(.30f);
        scene.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(a.dp(28), a.dp(14), a.dp(24), a.dp(8));
        scene.addView(layout, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        layout.addView(header, new LinearLayout.LayoutParams(-1, a.dp(40)));
        TextView brand = a.label("WygTV", 28);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setTextColor(Theme.ACCENT);
        header.addView(brand, new LinearLayout.LayoutParams(-2, -1));
        a.status = a.label("", 13);
        a.status.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        a.status.setTextColor(Theme.MUTED);
        header.addView(a.status, new LinearLayout.LayoutParams(0, -1, 1));

        // The original Home's items as one row of shortcuts at the top.
        LinearLayout shortcuts = shortcuts(a);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, a.dp(46));
        slp.setMargins(0, a.dp(2), 0, a.dp(8));
        layout.addView(shortcuts, slp);
        shortcuts.getChildAt(0).requestFocus(); // Live TV, as on the classic Home

        // Spotlight: what the highlighted poster is.
        TextView title = a.label("Popular this week", 22);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        layout.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView facts = a.label("Movies and series people are watching now. Press OK on one for its details.", 13);
        facts.setTextColor(Theme.ACCENT);
        facts.setSingleLine(true);
        layout.addView(facts, new LinearLayout.LayoutParams(-1, -2));
        TextView plot = a.label("", 13);
        plot.setTextColor(Theme.MUTED);
        plot.setMaxLines(2);
        plot.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, a.dp(38));
        plp.bottomMargin = a.dp(4);
        layout.addView(plot, plp);

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));

        Spot spot = new Spot(a, generation, backdrop, title, facts, plot, shortcuts);
        JSONObject saved = readSaved(a);
        if (saved != null) fill(a, rows, saved, spot);
        else a.status.setText("Finding this week's popular movies and series in your playlists…");
        if (saved == null || System.currentTimeMillis() - saved.optLong("time") > KEEP_MS || !saved.optString("for").equals(sourcesKey(a)))
            refresh(a, generation, rows, spot);
        else AutoRefresh.show(a, a.playlistsLine());
    }

    /** The spotlight texts and picture, updated as posters are highlighted. */
    private static final class Spot {
        final MainActivity a; final int generation; final ImageView backdrop; final TextView title, facts, plot; final View top;
        Spot(MainActivity a, int generation, ImageView backdrop, TextView title, TextView facts, TextView plot, View top) {
            this.a = a; this.generation = generation; this.backdrop = backdrop; this.title = title; this.facts = facts; this.plot = plot; this.top = top;
        }
        void show(JSONObject it, boolean series) {
            title.setText(it.optString("_title", it.optString("name", "")));
            StringBuilder b = new StringBuilder(series ? "Series" : "Movie");
            if (!it.optString("_year").isEmpty()) b.append("  •  ").append(it.optString("_year"));
            if (!it.optString("_rating").isEmpty()) b.append("  •  ").append(it.optString("_rating"));
            if (!it.optString("_source").isEmpty()) b.append("  •  popular on ").append(it.optString("_source"));
            facts.setText(b);
            plot.setText(it.optString("_overview"));
            String art = it.optString("_backdrop");
            if (art.isEmpty()) art = it.optString("_poster"); // no wide picture from this source: the poster, faded behind
            if (!art.isEmpty()) Details.image(backdrop, art, a.getResources().getDisplayMetrics().widthPixels, generation, a);
        }
    }

    /** Looks up the popular titles and matches them to the playlists on a background thread, then redraws the rows. */
    private static void refresh(MainActivity a, int generation, LinearLayout rows, Spot spot) {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            JSONObject result = null;
            try { result = build(a); } catch (OutOfMemoryError e) { System.gc(); } catch (Throwable ignored) { }
            final JSONObject got = result;
            a.runOnUiThread(() -> {
                loading = false;
                if (a.isFinishing() || a.isDestroyed()) return;
                if (got != null) save(a, got);
                if (generation != a.screenGeneration || !"home".equals(a.mode)) return;
                if (got == null) { a.status.setText("Couldn't load the popular titles right now. They'll be tried again next time."); return; }
                fill(a, rows, got, spot);
                AutoRefresh.show(a, a.playlistsLine());
            });
        }, "showcase").start();
    }

    /** The popular rows, from public sources that need no key (see {@link PopularSources}). */
    private static JSONObject build(MainActivity a) throws Exception {
        JSONObject out = new JSONObject();
        out.put("time", System.currentTimeMillis());
        out.put("for", sourcesKey(a));
        out.put("vod", PopularSources.movies(PER_ROW * 2));
        out.put("series", PopularSources.series(PER_ROW * 2));
        return out;
    }

    /** Which playlists the saved rows were made from, so switching playlists on or off looks them up again. */
    private static String sourcesKey(MainActivity a) { return "public-v1"; }

    private static void fill(MainActivity a, LinearLayout rows, JSONObject data, Spot spot) {
        rows.removeAllViews();
        boolean tmdb = data.optBoolean("tmdb");
        JSONArray movies = data.optJSONArray("vod"), series = data.optJSONArray("series");
        View first = null;
        if (movies != null && movies.length() > 0) first = row(a, rows, "Popular movies", "vod", movies, spot);
        if (series != null && series.length() > 0) { View f = row(a, rows, "Popular series", "series", series, spot); if (first == null) first = f; }
        if (rows.getChildCount() == 0) {
            TextView none = a.label("Couldn't load what's popular right now. Check the internet connection; it's tried again next time. Movies and Series are in the side menu.", 16);
            none.setTextColor(Theme.MUTED);
            none.setPadding(0, a.dp(20), 0, 0);
            rows.addView(none);
        }
        // The rows load after the screen opens: don't pull the highlight off the shortcuts row.
        if (first != null && !a.isFinishing() && !spot.top.hasFocus()) first.requestFocus();
    }

    /**
     * The classic Home's items, small, in one row: Live TV, TV Guide, Movies, Series, Search, a 📡 tile for each
     * antenna tuner (and AirTV), Playlists, Settings and Exit. Live TV is highlighted when the screen opens.
     */
    private static LinearLayout shortcuts(MainActivity a) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        shortcut(a, row, "▣  Live TV", () -> a.browse("live"));
        shortcut(a, row, "▦  TV Guide", () -> a.openGuide(null));
        if (a.library.hasVod()) {
            shortcut(a, row, "▶  Movies", () -> a.browseVod("vod"));
            shortcut(a, row, "▤  Series", () -> a.browseVod("series"));
        }
        shortcut(a, row, "⌕  Search", () -> MasterSearch.show(a));
        for (JSONObject t : Antenna.tuners(a)) {
            String aid = Antenna.PREFIX + t.optString("id");
            shortcut(a, row, "📡  " + Antenna.nameOf(t), () -> a.showGuide(aid, null, true));
        }
        if (AirTv.isOn(a.prefs)) shortcut(a, row, "📡  " + AirTv.nameOf(a.prefs), () -> AirTv.open(a));
        if (TabloApp.isOn(a.prefs)) shortcut(a, row, "📡  " + TabloApp.nameOf(a.prefs), () -> TabloApp.open(a));
        shortcut(a, row, "☰  Playlists", () -> { Menus.playlistsFromSettings = false; a.playlists(); });
        shortcut(a, row, "⚙  Settings", a::settings);
        if (MenuOrder.homeExit(a.prefs)) shortcut(a, row, "⏻  Exit", a::confirmExit);
        return row;
    }

    private static TextView shortcut(MainActivity a, LinearLayout row, String text, Runnable action) {
        TextView t = a.label(text, 15);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(a.dp(6), 0, a.dp(6), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(Skin.tile(a, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMargins(a.dp(4), a.dp(3), a.dp(4), a.dp(3));
        row.addView(t, lp);
        t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, focused) -> {
            t.setBackground(Skin.tile(a, focused));
            t.setTextColor(focused ? Theme.INK : Theme.TEXT);
            t.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(120).start();
        });
        return t;
    }

    /** One titled row of posters that scrolls sideways. Returns its first poster. */
    private static View row(MainActivity a, LinearLayout rows, String heading, String type, JSONArray items, Spot spot) {
        TextView h = a.label(heading, 17);
        h.setTypeface(null, Typeface.BOLD);
        h.setTextColor(Theme.ACCENT);
        h.setPadding(a.dp(4), a.dp(4), 0, a.dp(2));
        rows.addView(h, new LinearLayout.LayoutParams(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        rows.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout strip = new LinearLayout(a);
        strip.setPadding(a.dp(2), a.dp(4), a.dp(40), a.dp(6));
        hs.addView(strip, new FrameLayout.LayoutParams(-2, -2));
        View first = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            View t = tile(a, strip, type, it, items, spot);
            if (first == null) first = t;
        }
        return first;
    }

    private static View tile(MainActivity a, LinearLayout strip, String type, JSONObject it, JSONArray items, Spot spot) {
        boolean series = type.equals("series");
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
        t.setBackground(Skin.tile(a, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(112), -2);
        lp.setMargins(a.dp(5), a.dp(4), a.dp(5), a.dp(4));
        strip.addView(t, lp);
        String name = it.optString("_title", it.optString("name", "Untitled"));
        FrameLayout art = new FrameLayout(a);
        t.addView(art, new LinearLayout.LayoutParams(-1, a.dp(156)));
        TextView placeholder = a.label(name, 13);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTextColor(Theme.MUTED);
        placeholder.setPadding(a.dp(4), 0, a.dp(4), 0);
        art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        String url = it.optString("_poster");
        if (url.isEmpty()) url = it.optString(series ? "cover" : "stream_icon");
        Details.image(poster, url, a.dp(112), spot.generation, a);
        TextView label = a.label(name, 12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(a.dp(2), a.dp(4), a.dp(2), 0);
        t.addView(label, new LinearLayout.LayoutParams(-1, -2));
        t.setOnFocusChangeListener((v, focused) -> {
            t.setBackground(Skin.tile(a, focused));
            t.setScaleX(focused ? 1.06f : 1f);
            t.setScaleY(focused ? 1.06f : 1f);
            if (focused) spot.show(it, series);
        });
        t.setOnClickListener(v -> open(a, type, it, items));
        return t;
    }

    /**
     * OK on a poster: opens its details page straight away, like Movies and Series, which then looks for the title
     * in your playlists (see {@link PosterDetails}).
     */
    private static void open(MainActivity a, String type, JSONObject poster, JSONArray row) { PosterDetails.open(a, type, poster); }

    /**
     * Plays the title's trailer: TMDb's when a TMDb key is set, else (or when none is found) a YouTube search for
     * "title year trailer", which needs no key.
     */
    static void trailer(MainActivity a, boolean series, JSONObject poster) {
        Toast.makeText(a, "Finding the trailer…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String key = "";
            try {
                String id = poster.optString("_tmdb");
                if (id.isEmpty()) id = Tmdb.findId(a, series, poster.optString("_title"), poster.optString("_year"));
                if (!id.isEmpty()) key = Tmdb.extras(a, series, id).optString("trailer");
            } catch (Throwable ignored) { }
            final String k = key;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                JSONObject m = new JSONObject();
                try { m.put("trailer", k).put("title", poster.optString("_title")).put("year", poster.optString("_year")); } catch (org.json.JSONException ignored) { }
                Details.trailer(a, m, poster.optString("_title"));
            });
        }, "showcase-trailer").start();
    }

    /** Lists the streaming services that have the title in this country (from TMDb / JustWatch). */
    static void whereToWatch(MainActivity a, boolean series, JSONObject poster) {
        String title = poster.optString("_title");
        android.app.AlertDialog wait = new Dialogs.Builder(a).setTitle(title).setMessage("Looking up where it's available…").setNegativeButton("Cancel", null).show();
        new Thread(() -> {
            JSONObject x = null;
            try {
                String id = poster.optString("_tmdb");
                if (id.isEmpty()) id = Tmdb.findId(a, series, poster.optString("_title"), poster.optString("_year"));
                x = id.isEmpty() ? new JSONObject() : Tmdb.extras(a, series, id);
            } catch (Throwable ignored) { }
            final JSONObject got = x;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || !wait.isShowing()) return;
                wait.dismiss();
                StringBuilder b = new StringBuilder();
                if (got != null) {
                    if (!got.optString("stream").isEmpty()) b.append("Stream with a subscription:\n").append(got.optString("stream")).append("\n\n");
                    if (!got.optString("free").isEmpty()) b.append("Free:\n").append(got.optString("free")).append("\n\n");
                    if (!got.optString("rent").isEmpty()) b.append("Rent:\n").append(got.optString("rent")).append("\n\n");
                    if (!got.optString("buy").isEmpty()) b.append("Buy:\n").append(got.optString("buy")).append("\n\n");
                }
                String msg = got == null ? "Couldn't look it up right now. Check the internet connection."
                        : b.length() == 0 ? "No streaming services are listed for it in your country yet." + (series ? "" : " New movies often reach rental and streaming services a few weeks after the cinema.")
                        : b.append("Availability from JustWatch, via TMDb.").toString();
                new Dialogs.Builder(a).setTitle(title + "  •  where to watch").setMessage(msg)
                        .setPositiveButton("▷  Trailer", (d, w) -> trailer(a, series, poster))
                        .setNegativeButton("Close", null).show();
            });
        }, "showcase-where").start();
    }

    private static File file(MainActivity a) { return new File(a.getFilesDir(), "showcase.json"); }

    private static JSONObject readSaved(MainActivity a) {
        File f = file(a);
        if (!f.exists() || f.length() > 4_000_000) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (Throwable e) { return null; }
    }

    private static void save(MainActivity a, JSONObject data) {
        try (FileOutputStream out = new FileOutputStream(file(a))) { out.write(data.toString().getBytes(StandardCharsets.UTF_8)); } catch (Throwable ignored) { }
    }

    /** Forgets the saved rows (e.g. when playlists update), so the next visit looks them up again. */
    static void forget(MainActivity a) { file(a).delete(); PosterDetails.forget(); }
}
