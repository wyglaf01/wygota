package com.wygtv.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The movie / series details screen: poster, backdrop, year, runtime, rating, age rating, genres, plot, director and
 * cast, with three buttons: Play (or Episodes for a series), My List and Trailer. It shows the list's own info at once, then the provider's
 * get_vod_info / get_series_info, then TMDb's when a key is set (see {@link Tmdb}).
 */
final class Details {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private Details() { }

    /**
     * Opens the details of a movie ("vod") or series row. {@code category} and {@code list} are where it was picked,
     * so Back (and the player's Back) return to the same row.
     */
    static void open(MainActivity a, String type, JSONObject item, String category, JSONArray list) { open(a, type, item, category, list, null); }

    /** Same, but Back goes to {@code back} (e.g. the master Search) instead of the movie/series list. */
    static void open(MainActivity a, String type, JSONObject item, String category, JSONArray list, Runnable back) {
        boolean series = type.equals("series");
        String id = item.optString(series ? "series_id" : "stream_id");
        String name = item.optString("name", "Untitled");
        String ext = item.optString("container_extension", "mp4");
        Runnable reopen = () -> open(a, type, item, category, list, back);

        a.detailsType = type;
        a.mode = "details";
        a.detailsBack = back != null ? back : () -> { a.resumeCategory = category; a.resumeStreamId = id; a.browse(type); };
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
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(a.PANEL));
        poster.setClipToOutline(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(a.dp(220), a.dp(330));
        plp.setMargins(0, a.dp(6), a.dp(28), 0);
        row.addView(poster, plp);

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        row.addView(sv, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);

        TextView title = text(a, col, 32, Theme.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        TextView facts = text(a, col, 17, Theme.MUTED);
        TextView genres = text(a, col, 16, Theme.ACCENT);

        LinearLayout buttons = new LinearLayout(a);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMargins(0, a.dp(10), 0, a.dp(8));
        col.addView(buttons, blp);
        String progress = series ? Watch.summary(Watch.load(a, id)) : "";
        TextView play = action(a, buttons, series ? (progress.isEmpty() ? "☰  Episodes" : "▶  Episodes  •  " + progress) : "▶  Play", () -> {
            if (series) { a.episodesBack = reopen; Episodes.show(a, id, name); return; }
            a.playerReturn = reopen;
            a.zapList = list;
            a.play("vod", id, ext, name);
        });
        TextView[] mine = new TextView[1];
        mine[0] = action(a, buttons, listLabel(a, type, id), () -> {
            boolean added = a.toggleMyList(type, item);
            mine[0].setText(listLabel(a, type, id));
            Toast.makeText(a, added ? "Added to My List" : "Removed from My List", Toast.LENGTH_SHORT).show();
        });
        JSONObject[] shown = {Tmdb.fromProvider(item, null)};
        action(a, buttons, "▷  Trailer", () -> trailer(a, shown[0], name));
        // Like the main screen's posters: where else it's streaming (needs the optional TMDb key).
        if (!Tmdb.key(a).isEmpty()) action(a, buttons, "◎  Where to watch", () -> {
            JSONObject m = shown[0], p = new JSONObject();
            try { p.put("_title", m.optString("title", Tmdb.cleanTitle(name)[0])).put("_year", m.optString("year")).put("_tmdb", m.optString("tmdb_id")); } catch (org.json.JSONException ignored) { }
            Showcase.whereToWatch(a, series, p);
        });

        TextView tagline = text(a, col, 17, Theme.ACCENT);
        tagline.setTypeface(null, Typeface.ITALIC);
        TextView plot = text(a, col, 19, Theme.TEXT);
        plot.setLineSpacing(0, 1.12f);
        // Focusable so Down can scroll a long plot into view; Up goes back to the buttons.
        plot.setFocusable(true);
        plot.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        plot.setOnFocusChangeListener((v, f) -> plot.setBackgroundColor(f ? Theme.alpha(Theme.FOCUS, 0x40) : 0));
        TextView director = text(a, col, 17, Theme.TEXT);
        TextView cast = text(a, col, 17, Theme.TEXT);
        TextView source = text(a, col, 13, Theme.HINT);
        source.setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(10));

        JSONObject[] provider = {shown[0]};
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            JSONObject m = shown[0];
            title.setText(m.optString("title", name));
            facts.setText(facts(m, series));
            show(genres, m.optString("genres"));
            show(tagline, m.optString("tagline"));
            show(plot, m.optString("plot"));
            plot.setFocusable(!m.optString("plot").isEmpty());
            show(director, m.optString("director").isEmpty() ? "" : m.optString("director_label", "Director") + ":  " + m.optString("director"));
            show(cast, m.optString("cast").isEmpty() ? "" : "Cast:  " + m.optString("cast"));
            source.setText(sourceLine(a, m));
            image(poster, m.optString("poster"), a.dp(220), generation, a);
            image(backdrop, m.optString("backdrop"), a.dp(640), generation, a);
        };
        render[0].run();
        play.requestFocus();

        if (a.library.activeIsM3u()) return;
        a.apiQuiet((series ? "get_series_info&series_id=" : "get_vod_info&vod_id=") + a.enc(id), answer -> {
            if (generation != a.screenGeneration) return;
            if (answer != null) { provider[0] = Tmdb.fromProvider(item, answer); shown[0] = provider[0]; render[0].run(); }
            if (!Tmdb.enabled(a)) return;
            JSONObject p = provider[0];
            POOL.execute(() -> {
                JSONObject t = Tmdb.enrich(a, type, p, name);
                UI.post(() -> {
                    if (generation != a.screenGeneration || a.isFinishing()) return;
                    if (t == null) { source.setText(sourceLine(a, shown[0]) + "  •  no TMDb match"); return; }
                    shown[0] = t;
                    render[0].run();
                });
            });
        });
    }

    /** "2022  •  2h 56m  •  PG-13  •  ★ 7.7 TMDb  •  3 seasons" */
    static String facts(JSONObject m, boolean series) {
        List<String> parts = new ArrayList<>();
        if (!m.optString("year").isEmpty()) parts.add(m.optString("year"));
        int min = m.optInt("runtime", 0);
        if (min > 0) parts.add((series ? "~" : "") + (min >= 60 ? min / 60 + "h " + (min % 60 > 0 ? min % 60 + "m" : "") : min + "m").trim());
        if (!m.optString("cert").isEmpty()) parts.add(m.optString("cert"));
        if (!m.optString("imdb_rating").isEmpty()) parts.add("★ " + m.optString("imdb_rating") + " IMDb");
        else if (!m.optString("rating").isEmpty()) parts.add("★ " + m.optString("rating") + ("TMDb".equals(m.optString("rating_source")) ? " TMDb" : ""));
        if (!m.optString("rt").isEmpty()) parts.add("Rotten Tomatoes " + m.optString("rt"));
        if (!m.optString("metascore").isEmpty()) parts.add("Metacritic " + m.optString("metascore"));
        int seasons = m.optInt("seasons", 0);
        if (series && seasons > 0) parts.add(seasons + (seasons == 1 ? " season" : " seasons"));
        return android.text.TextUtils.join("   •   ", parts);
    }

    static String sourceLine(MainActivity a, JSONObject m) {
        boolean tmdb = "TMDb".equals(m.optString("source")), omdb = m.has("omdb");
        if (tmdb || omdb) return "Details from " + (tmdb ? "TMDb" : "") + (tmdb && omdb ? ", " : "") + (omdb ? "OMDb (IMDb ratings)" : "") + " and your provider.";
        if (!Tmdb.enabled(a)) return "Details from your provider.  Add a free TMDb or OMDb API key in Settings for posters, plots, cast and IMDb ratings.";
        return "Details from your provider.";
    }

    /**
     * Plays the trailer in the YouTube app (Android TV's, else the phone one), else a browser. With no trailer
     * known (no TMDb key, nothing from the provider) it opens a YouTube search for "title year trailer".
     */
    static void trailer(MainActivity a, JSONObject m, String rawName) {
        String key = m.optString("trailer");
        String title = m.optString("title", Tmdb.cleanTitle(rawName)[0]), year = m.optString("year");
        String query = (title + " " + year).trim() + " trailer";
        java.util.List<android.content.Intent> tries = new ArrayList<>();
        if (!key.isEmpty()) {
            for (String pkg : new String[]{"com.google.android.youtube.tv", "com.google.android.youtube", null}) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=" + key));
                if (pkg != null) i.setPackage(pkg);
                tries.add(i);
            }
        } else {
            for (String pkg : new String[]{"com.google.android.youtube.tv", "com.google.android.youtube"}) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEARCH);
                i.setPackage(pkg);
                i.putExtra(android.app.SearchManager.QUERY, query);
                i.putExtra("query", query);
                tries.add(i);
            }
            tries.add(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query))));
        }
        for (android.content.Intent i : tries) {
            try { a.startActivity(i); return; }
            catch (android.content.ActivityNotFoundException | SecurityException ignored) { }
        }
        Toast.makeText(a, "Install YouTube to watch trailers.", Toast.LENGTH_LONG).show();
    }

    private static String listLabel(MainActivity a, String type, String id) { return a.inMyList(type, id) ? "✓  My List" : "＋  My List"; }

    static TextView action(MainActivity a, LinearLayout parent, String label, Runnable r) {
        TextView v = a.button(label, parent, r);
        v.setTextSize(19);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, a.dp(4), a.dp(14), a.dp(4));
        v.setLayoutParams(lp);
        v.setMinWidth(a.dp(170));
        return v;
    }

    static TextView text(MainActivity a, LinearLayout parent, int size, int color) {
        TextView t = a.label("", size);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    static void show(TextView t, String s) {
        t.setText(s);
        t.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ images

    /** Loads a poster / backdrop into the view, scaled down to about maxWidth pixels; later calls on the view win. */
    static void image(ImageView v, String url, int maxWidth, int generation, MainActivity a) {
        if (url == null || url.isEmpty()) return;
        if (url.equals(v.getTag())) return;
        v.setTag(url);
        Bitmap cached = IMAGES.get(url);
        if (cached != null) { v.setImageBitmap(cached); return; }
        POOL.execute(() -> {
            Bitmap b = download(a.getCacheDir(), url, maxWidth);
            if (b == null) return;
            IMAGES.put(url, b);
            UI.post(() -> { if (url.equals(v.getTag()) && generation == a.screenGeneration) v.setImageBitmap(b); });
        });
    }

    /** Downloads (or reads from the disk cache) an image, shrunk to about maxWidth pixels. */
    private static Bitmap download(java.io.File cacheDir, String url, int maxWidth) {
        java.io.File disk = new java.io.File(cacheDir, "posters/" + M3u.idFor(url + "|" + maxWidth) + ".jpg");
        if (disk.exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(disk.getAbsolutePath(), o);
            if (b != null) return b;
        }
        try {
            HttpURLConnection conn = Library.open(url, 15000);
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > 8_000_000) return null; }
                bytes = out.toByteArray();
            } finally { conn.disconnect(); }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (b == null) return null;
            if (b.getWidth() > maxWidth * 3 / 2) b = Bitmap.createScaledBitmap(b, maxWidth, Math.max(1, b.getHeight() * maxWidth / b.getWidth()), true);
            java.io.File dir = disk.getParentFile();
            if (dir != null) dir.mkdirs();
            try (java.io.OutputStream out = new java.io.FileOutputStream(disk)) { b.compress(Bitmap.CompressFormat.JPEG, 88, out); } catch (Exception ignored) { }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }
}
