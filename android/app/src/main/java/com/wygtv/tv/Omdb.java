package com.wygtv.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URLEncoder;

/**
 * OMDb (omdbapi.com) for IMDb ratings, Rotten Tomatoes and Metacritic scores, and as a second source for plot,
 * cast, director, age rating and poster when the provider and TMDb have none. Looked up by IMDb id (from TMDb)
 * when known, else by title and year. Needs a free OMDb key (Settings, or the GitHub secret OMDB_API_KEY).
 */
final class Omdb {
    static final String PREF_KEY = "omdb_key";
    private static final long KEEP_MS = 14L * 24 * 3600 * 1000, KEEP_MISS_MS = 2L * 24 * 3600 * 1000;

    private Omdb() { }

    static String key(Context c) {
        String mine = c.getSharedPreferences("wygtv", Context.MODE_PRIVATE).getString(PREF_KEY, "").trim();
        return mine.isEmpty() ? builtInKey() : mine;
    }

    static String builtInKey() { return BuildConfig.OMDB_API_KEY == null ? "" : BuildConfig.OMDB_API_KEY.trim(); }

    /** The meta fields from an OMDb answer, or null when it says "Response": "False". */
    static JSONObject fromOmdb(JSONObject o) {
        if (o == null || !"True".equalsIgnoreCase(o.optString("Response"))) return null;
        JSONObject m = new JSONObject();
        put(m, "title", o.optString("Title"));
        put(m, "year", Tmdb.yearOf(o.optString("Year")));
        put(m, "cert", o.optString("Rated"));
        int min = Tmdb.minutes(o.optString("Runtime"));
        if (min > 0) put(m, "runtime", String.valueOf(min));
        put(m, "genres", o.optString("Genre"));
        put(m, "director", o.optString("Director"));
        put(m, "cast", o.optString("Actors"));
        put(m, "plot", o.optString("Plot"));
        String poster = o.optString("Poster");
        if (poster.startsWith("http")) put(m, "poster", poster);
        if (o.optString("imdbID").startsWith("tt")) put(m, "imdb", o.optString("imdbID"));
        put(m, "imdb_rating", Tmdb.rating(o.optString("imdbRating")));
        String meta = o.optString("Metascore");
        if (meta.matches("\\d{1,3}")) put(m, "metascore", meta);
        JSONArray ratings = o.optJSONArray("Ratings");
        for (int i = 0; ratings != null && i < ratings.length(); i++) {
            JSONObject r = ratings.optJSONObject(i);
            if (r != null && "Rotten Tomatoes".equals(r.optString("Source"))) put(m, "rt", r.optString("Value"));
        }
        String seasons = o.optString("totalSeasons");
        if (seasons.matches("\\d{1,3}")) put(m, "seasons", seasons);
        return m;
    }

    /** OMDb's ratings always added; its other fields only fill what's still empty. */
    static JSONObject merge(JSONObject meta, JSONObject omdb) {
        JSONObject out = new JSONObject();
        try {
            for (java.util.Iterator<String> k = meta.keys(); k.hasNext(); ) { String n = k.next(); out.put(n, meta.opt(n)); }
            if (omdb == null) return out;
            for (String n : new String[]{"imdb_rating", "rt", "metascore"}) if (omdb.has(n)) out.put(n, omdb.optString(n));
            for (String n : new String[]{"year", "cert", "runtime", "genres", "director", "cast", "plot", "poster", "imdb", "seasons"})
                if (out.optString(n).isEmpty() && omdb.has(n)) out.put(n, omdb.optString(n));
            if (omdb.has("director") && out.optString("director_label").isEmpty()) out.put("director_label", "Director");
            out.put("omdb", "true");
        } catch (Exception ignored) { }
        return out;
    }

    /** OMDb meta by IMDb id, else by title + year. Cached on disk for two weeks. Off the UI thread; null on no match. */
    static JSONObject lookup(Context c, String type, String imdbId, String title, String year) {
        String key = key(c);
        if (key.isEmpty()) return null;
        File cache = new File(c.getCacheDir(), "omdb/" + M3u.idFor(type + "|" + imdbId + "|" + title + "|" + year) + ".json");
        JSONObject saved = Tmdb.cached(cache, KEEP_MS, KEEP_MISS_MS);
        if (saved != null) return saved.optBoolean("miss") ? null : saved.optJSONObject("meta");
        try {
            String kind = type.equals("series") ? "series" : "movie";
            String q = imdbId.startsWith("tt") ? "i=" + imdbId : "t=" + URLEncoder.encode(title, "UTF-8") + "&type=" + kind + (year.isEmpty() ? "" : "&y=" + year);
            JSONObject m = fromOmdb(get(key, q + "&plot=full"));
            if (m == null && !year.isEmpty() && !imdbId.startsWith("tt"))
                m = fromOmdb(get(key, "t=" + URLEncoder.encode(title, "UTF-8") + "&type=" + kind + "&plot=full"));
            if (m != null && !year.isEmpty() && !imdbId.startsWith("tt") && !m.optString("year").isEmpty()
                    && Math.abs(Integer.parseInt(m.optString("year")) - Integer.parseInt(year)) > 1) m = null;
            Tmdb.save(cache, m);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when OMDb accepts the key (for Settings). */
    static boolean works(String key) {
        try {
            JSONObject o = get(key, "i=tt0111161");
            return "True".equalsIgnoreCase(o.optString("Response"));
        } catch (Exception e) { return false; }
    }

    private static JSONObject get(String key, String query) throws Exception {
        HttpURLConnection conn = Library.open("https://www.omdbapi.com/?apikey=" + URLEncoder.encode(key, "UTF-8") + "&" + query, 15000);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new java.io.IOException("OMDb answered " + code);
            return new JSONObject(Tmdb.read(conn.getInputStream()));
        } finally { conn.disconnect(); }
    }

    private static void put(JSONObject o, String k, String v) {
        if (v == null) return;
        v = v.trim();
        if (v.isEmpty() || v.equals("N/A")) return;
        try { o.put(k, v); } catch (Exception ignored) { }
    }
}
