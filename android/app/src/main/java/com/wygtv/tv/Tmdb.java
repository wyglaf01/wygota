package com.wygtv.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Movie and series details (poster, plot, rating, cast, year...) for the details screen. The provider's own
 * get_vod_info / get_series_info answer is always used; with a TMDb API key (Settings, or built in at compile
 * time) the matching TMDb entry fills in and replaces what the provider left out.
 *
 * Both sources are turned into the same flat "meta" object: title, year, runtime (minutes), rating, rating_source,
 * cert, genres, tagline, plot, director, director_label, cast, poster, backdrop, seasons, imdb.
 */
final class Tmdb {
    static final String PREF_KEY = "tmdb_key";
    private static final String API = "https://api.themoviedb.org/3/";
    private static final String IMAGES = "https://image.tmdb.org/t/p/";
    private static final long KEEP_MS = 14L * 24 * 3600 * 1000, KEEP_MISS_MS = 2L * 24 * 3600 * 1000;

    private Tmdb() { }

    /** The key from Settings, else the one built into the app (GitHub secret TMDB_API_KEY), else "". */
    static String key(Context c) {
        String mine = c.getSharedPreferences("wygtv", Context.MODE_PRIVATE).getString(PREF_KEY, "").trim();
        return mine.isEmpty() ? builtInKey() : mine;
    }

    static String builtInKey() { return BuildConfig.TMDB_API_KEY == null ? "" : BuildConfig.TMDB_API_KEY.trim(); }

    // ------------------------------------------------------------------ titles

    private static final Pattern LEAD_TAGS = Pattern.compile("^\\s*(?:[\\[|(][^\\]|)]{1,12}[\\]|)]\\s*[-:|]?\\s*)+");
    private static final Pattern LEAD_LANG = Pattern.compile("^\\s*[A-Z]{2,3}(?:[-/][A-Z0-9]{2,3})?\\s*[-:|]\\s+");
    private static final Pattern YEAR_BRACKETS = Pattern.compile("[(\\[]\\s*((?:19|20)\\d\\d)\\s*[)\\]]");
    private static final Pattern YEAR_TAIL = Pattern.compile("(?:\\s+-\\s*|\\s+)((?:19|20)\\d\\d)\\s*$");
    private static final Pattern BRACKETS = Pattern.compile("[(\\[][^)\\]]*[)\\]]");
    private static final Pattern QUALITY = Pattern.compile("(?i)(?<![\\p{L}\\d])(?:4k|8k|uhd|fhd|hd|sd|hdr10?|hdr|dv|hevc|h\\.?26[45]|x26[45]|2160p|1080p|720p|480p|multi|multisub|vostfr|vost|dual|dubbed|subbed|imax)(?![\\p{L}\\d])");

    /** "EN - The Batman (2022) 4K" → {"The Batman", "2022"}. The year is "" when the name has none. */
    static String[] cleanTitle(String raw) {
        String s = raw == null ? "" : raw.replace('_', ' ').replace('.', ' ').trim();
        s = LEAD_TAGS.matcher(s).replaceFirst("");
        s = LEAD_LANG.matcher(s).replaceFirst("");
        String year = "";
        Matcher m = YEAR_BRACKETS.matcher(s);
        if (m.find()) { year = m.group(1); s = s.substring(0, m.start()) + " " + s.substring(m.end()); }
        s = BRACKETS.matcher(s).replaceAll(" ");
        s = QUALITY.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim();
        if (year.isEmpty()) {
            m = YEAR_TAIL.matcher(s);
            if (m.find() && m.start() > 0) { year = m.group(1); s = s.substring(0, m.start()); }
        }
        s = s.replaceAll("^[\\s\\-:|•]+|[\\s\\-:|•]+$", "").trim();
        if (s.isEmpty()) s = raw == null ? "" : raw.trim();
        return new String[]{s, year};
    }

    // ------------------------------------------------------------------ provider (Xtream) info

    /** The meta object from a list item plus the provider's get_vod_info / get_series_info answer (may be null). */
    static JSONObject fromProvider(JSONObject item, JSONObject answer) {
        JSONObject info = answer == null ? null : answer.optJSONObject("info");
        if (info == null) info = new JSONObject();
        if (item == null) item = new JSONObject();
        JSONObject meta = new JSONObject();
        String[] clean = cleanTitle(item.optString("name", info.optString("name")));
        put(meta, "title", clean[0]);
        String year = first(info, "releasedate", "releaseDate", "release_date", "year");
        if (year.isEmpty()) year = item.optString("year", "");
        year = yearOf(year);
        put(meta, "year", year.isEmpty() ? clean[1] : year);
        int runtime = 0;
        int secs = info.optInt("duration_secs", 0);
        if (secs > 0) runtime = secs / 60;
        else runtime = minutes(first(info, "duration", "episode_run_time", "runtime"));
        if (runtime > 0) put(meta, "runtime", String.valueOf(runtime));
        put(meta, "rating", rating(first(info, "rating")));
        if (meta.optString("rating").isEmpty()) put(meta, "rating", rating(item.optString("rating", "")));
        if (!meta.optString("rating").isEmpty()) put(meta, "rating_source", "provider");
        put(meta, "genres", first(info, "genre", "genres"));
        put(meta, "plot", first(info, "plot", "description", "overview"));
        put(meta, "director", first(info, "director"));
        put(meta, "director_label", "Director");
        put(meta, "cast", first(info, "cast", "actors"));
        put(meta, "cert", first(info, "mpaa_rating", "age", "certification"));
        put(meta, "poster", url(first(info, "movie_image", "cover_big", "cover")));
        if (meta.optString("poster").isEmpty()) put(meta, "poster", url(first(item, "stream_icon", "cover")));
        put(meta, "backdrop", url(firstOf(info.opt("backdrop_path"))));
        if (meta.optString("backdrop").isEmpty()) put(meta, "backdrop", url(firstOf(item.opt("backdrop_path"))));
        JSONArray seasons = answer == null ? null : answer.optJSONArray("seasons");
        JSONObject episodes = answer == null ? null : answer.optJSONObject("episodes");
        int count = seasons != null && seasons.length() > 0 ? seasons.length() : episodes != null ? episodes.length() : 0;
        if (count > 0) put(meta, "seasons", String.valueOf(count));
        String id = digits(first(info, "tmdb_id", "tmdb"));
        if (id.isEmpty()) id = digits(first(item, "tmdb_id", "tmdb"));
        put(meta, "tmdb_id", id);
        put(meta, "trailer", youtubeKey(first(info, "youtube_trailer", "trailer")));
        return meta;
    }

    /**
     * The YouTube video id in a provider's trailer field, which may be a bare id ("dQw4w9WgXcQ") or a full
     * youtube.com / youtu.be link. Empty when there's none.
     */
    static String youtubeKey(String s) {
        if (s == null) return "";
        s = s.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})").matcher(s);
        if (m.find()) return m.group(1);
        return s.matches("[A-Za-z0-9_-]{11}") ? s : "";
    }

    /** The best YouTube trailer in a TMDb "videos" answer: official trailers first, then any trailer, then a teaser. */
    static String trailerFrom(JSONObject videos) {
        JSONArray r = videos == null ? null : videos.optJSONArray("results");
        String best = "";
        int bestScore = 0;
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject v = r.optJSONObject(i);
            if (v == null || !"YouTube".equalsIgnoreCase(v.optString("site")) || v.optString("key").isEmpty()) continue;
            String type = v.optString("type");
            int score = type.equals("Trailer") ? 4 : type.equals("Teaser") ? 2 : 1;
            if (v.optBoolean("official")) score++;
            if (score > bestScore) { bestScore = score; best = v.optString("key"); }
        }
        return best;
    }

    // ------------------------------------------------------------------ TMDb answers

    /** The meta object from a TMDb /movie/{id} or /tv/{id} answer (with credits, release_dates / content_ratings). */
    static JSONObject fromTmdb(JSONObject t, boolean tv) {
        JSONObject meta = new JSONObject();
        put(meta, "title", t.optString(tv ? "name" : "title"));
        put(meta, "year", yearOf(t.optString(tv ? "first_air_date" : "release_date")));
        int runtime = tv ? firstInt(t.optJSONArray("episode_run_time")) : t.optInt("runtime", 0);
        if (runtime > 0) put(meta, "runtime", String.valueOf(runtime));
        if (t.optInt("vote_count", 0) > 0 && t.optDouble("vote_average", 0) > 0) {
            put(meta, "rating", String.format(Locale.US, "%.1f", t.optDouble("vote_average")));
            put(meta, "rating_source", "TMDb");
        }
        put(meta, "genres", names(t.optJSONArray("genres"), 4));
        put(meta, "tagline", t.optString("tagline"));
        put(meta, "plot", t.optString("overview"));
        JSONObject credits = t.optJSONObject("credits");
        if (tv) {
            put(meta, "director", names(t.optJSONArray("created_by"), 3));
            put(meta, "director_label", "Created by");
            if (t.optInt("number_of_seasons", 0) > 0) put(meta, "seasons", String.valueOf(t.optInt("number_of_seasons")));
        } else if (credits != null) {
            StringBuilder d = new StringBuilder();
            JSONArray crew = credits.optJSONArray("crew");
            for (int i = 0; crew != null && i < crew.length(); i++) {
                JSONObject c = crew.optJSONObject(i);
                if (c != null && "Director".equals(c.optString("job"))) { if (d.length() > 0) d.append(", "); d.append(c.optString("name")); }
            }
            put(meta, "director", d.toString());
            put(meta, "director_label", "Director");
        }
        if (credits != null) put(meta, "cast", names(credits.optJSONArray("cast"), 6));
        put(meta, "cert", certification(t, tv));
        String poster = t.optString("poster_path"), backdrop = t.optString("backdrop_path");
        if (!poster.isEmpty() && !"null".equals(poster)) put(meta, "poster", IMAGES + "w500" + poster);
        if (!backdrop.isEmpty() && !"null".equals(backdrop)) put(meta, "backdrop", IMAGES + "w1280" + backdrop);
        String imdb = t.optString("imdb_id");
        JSONObject ext = t.optJSONObject("external_ids");
        if ((imdb.isEmpty() || "null".equals(imdb)) && ext != null) imdb = ext.optString("imdb_id");
        if (imdb.startsWith("tt")) put(meta, "imdb", imdb);
        if (t.optInt("id", 0) > 0) put(meta, "tmdb_id", String.valueOf(t.optInt("id")));
        put(meta, "trailer", trailerFrom(t.optJSONObject("videos")));
        return meta;
    }

    /** The provider's meta with every non-empty TMDb value laid over it; the list title stays the provider's. */
    static JSONObject merge(JSONObject provider, JSONObject tmdb) {
        JSONObject out = new JSONObject();
        try {
            if (provider != null) for (java.util.Iterator<String> k = provider.keys(); k.hasNext(); ) { String n = k.next(); out.put(n, provider.opt(n)); }
            if (tmdb != null) for (java.util.Iterator<String> k = tmdb.keys(); k.hasNext(); ) {
                String n = k.next();
                if (n.equals("title") && !out.optString("title").isEmpty()) continue;
                if (n.equals("director_label") && tmdb.optString("director").isEmpty()) continue;
                String v = tmdb.optString(n);
                if (!v.isEmpty()) out.put(n, v);
            }
            if (tmdb != null) out.put("source", "TMDb");
        } catch (Exception ignored) { }
        return out;
    }

    /** Picks the search result to use: same year (±1) when the year is known, else an exact title, else the top one. */
    static JSONObject pick(JSONArray results, String title, String year, boolean tv) {
        if (results == null || results.length() == 0) return null;
        String want = XtreamProtocol.searchKey(title);
        JSONObject exact = null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String y = yearOf(r.optString(tv ? "first_air_date" : "release_date"));
            boolean sameName = XtreamProtocol.searchKey(r.optString(tv ? "name" : "title")).equals(want)
                    || XtreamProtocol.searchKey(r.optString(tv ? "original_name" : "original_title")).equals(want);
            if (!year.isEmpty()) {
                if (!y.isEmpty() && Math.abs(Integer.parseInt(y) - Integer.parseInt(year)) <= 1) return r;
            } else if (sameName && exact == null) exact = r;
        }
        if (!year.isEmpty()) return null;
        return exact != null ? exact : results.optJSONObject(0);
    }

    // ------------------------------------------------------------------ network (off the UI thread)

    /**
     * TMDb meta for a movie ("vod") or series, by TMDb id when the provider gave one, else by searching the
     * cleaned title. Cached on disk for two weeks (misses for two days). Null when there's no key or no match.
     */
    static JSONObject lookup(Context c, String type, String tmdbId, String title, String year) {
        String key = key(c);
        if (key.isEmpty()) return null;
        boolean tv = type.equals("series");
        String lang = Locale.getDefault().toLanguageTag();
        File cache = new File(c.getCacheDir(), "tmdb/" + M3u.idFor("v2|" + type + "|" + tmdbId + "|" + title + "|" + year + "|" + lang) + ".json");
        JSONObject saved = cached(cache, KEEP_MS, KEEP_MISS_MS);
        if (saved != null) return saved.optBoolean("miss") ? null : saved.optJSONObject("meta");
        try {
            String id = tmdbId;
            if (id.isEmpty()) {
                JSONObject hit = null;
                String q = (tv ? "search/tv?query=" : "search/movie?query=") + URLEncoder.encode(title, "UTF-8") + "&language=" + lang;
                if (!year.isEmpty()) hit = pick(get(key, q + (tv ? "&first_air_date_year=" : "&year=") + year).optJSONArray("results"), title, year, tv);
                if (hit == null) hit = pick(get(key, q).optJSONArray("results"), title, year, tv);
                if (hit == null) { save(cache, null); return null; }
                id = String.valueOf(hit.optInt("id"));
            }
            JSONObject full;
            try {
                full = get(key, (tv ? "tv/" : "movie/") + id + "?language=" + lang + "&append_to_response=credits,external_ids,videos," + (tv ? "content_ratings" : "release_dates")
                        + "&include_video_language=" + Locale.getDefault().getLanguage() + ",en,null");
            } catch (java.io.FileNotFoundException wrongKind) {
                // Some providers put a movie id on a series (or the reverse): search by title instead.
                if (tmdbId.isEmpty()) throw wrongKind;
                return lookup(c, type, "", title, year);
            }
            JSONObject meta = fromTmdb(full, tv);
            save(cache, meta);
            return meta;
        } catch (java.io.FileNotFoundException notFound) {
            save(cache, null);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A saved answer ({"saved", "meta"} or {"saved", "miss"}) still fresh enough to use, else null. */
    static JSONObject cached(File cache, long keepMs, long keepMissMs) {
        try {
            if (!cache.exists()) return null;
            JSONObject saved = new JSONObject(read(new java.io.FileInputStream(cache)));
            long age = System.currentTimeMillis() - saved.optLong("saved");
            return age < (saved.optBoolean("miss") ? keepMissMs : keepMs) ? saved : null;
        } catch (Exception e) { return null; }
    }

    /** True when a TMDb or an OMDb key is set, so lookups beyond the provider are worth doing. */
    static boolean enabled(Context c) { return !key(c).isEmpty() || !Omdb.key(c).isEmpty(); }

    /**
     * The provider's meta plus TMDb (when its key is set) and then OMDb's IMDb / Rotten Tomatoes / Metacritic
     * ratings (when its key is set). Off the UI thread. Null when neither found the title.
     */
    static JSONObject enrich(Context c, String type, JSONObject provider, String rawName) {
        String[] clean = cleanTitle(rawName);
        String year = provider.optString("year", clean[1]);
        JSONObject t = key(c).isEmpty() ? null : lookup(c, type, provider.optString("tmdb_id"), clean[0], year);
        JSONObject m = t == null ? provider : merge(provider, t);
        JSONObject o = Omdb.key(c).isEmpty() ? null : Omdb.lookup(c, type, m.optString("imdb"), clean[0], year);
        if (o != null) m = Omdb.merge(m, o);
        return t == null && o == null ? null : m;
    }

    /** True when TMDb accepts the key (for Settings). */
    /**
     * This week's trending movies or TV series on TMDb, most popular first ({@code pages} × 20 titles), for the main
     * screen. Empty when there is no TMDb key. Call off the UI thread.
     */
    static JSONArray trending(Context c, boolean tv, int pages) throws Exception {
        JSONArray out = new JSONArray();
        String key = key(c);
        if (key.isEmpty()) return out;
        for (int page = 1; page <= pages; page++) {
            JSONArray r = get(key, "trending/" + (tv ? "tv" : "movie") + "/week?page=" + page).optJSONArray("results");
            for (int i = 0; r != null && i < r.length(); i++) if (r.optJSONObject(i) != null) out.put(r.optJSONObject(i));
        }
        return out;
    }

    /**
     * A title's trailer and where it can be watched, for the main screen's titles that aren't in a playlist:
     * {"trailer": YouTube key, "stream"/"rent"/"buy"/"free": service names, "link": TMDb's watch page}. The services
     * are for this device's country (US when unknown); TMDb gets them from JustWatch. Call off the UI thread.
     */
    static JSONObject extras(Context c, boolean tv, String tmdbId) throws Exception {
        JSONObject out = new JSONObject();
        String key = key(c);
        if (key.isEmpty() || tmdbId == null || tmdbId.isEmpty()) return out;
        JSONObject t = get(key, (tv ? "tv/" : "movie/") + tmdbId + "?append_to_response=videos,watch/providers");
        out.put("trailer", trailerFrom(t.optJSONObject("videos")));
        JSONObject all = t.optJSONObject("watch/providers");
        all = all == null ? null : all.optJSONObject("results");
        String country = java.util.Locale.getDefault().getCountry();
        JSONObject here = all == null ? null : all.optJSONObject(country == null || country.isEmpty() ? "US" : country);
        if (here == null && all != null) here = all.optJSONObject("US");
        if (here != null) {
            out.put("link", here.optString("link"));
            String[][] kinds = {{"flatrate", "stream"}, {"free", "free"}, {"ads", "free"}, {"rent", "rent"}, {"buy", "buy"}};
            for (String[] k : kinds) {
                JSONArray list = here.optJSONArray(k[0]);
                StringBuilder b = new StringBuilder(out.optString(k[1]));
                for (int i = 0; list != null && i < list.length(); i++) {
                    String name = list.optJSONObject(i) == null ? "" : list.optJSONObject(i).optString("provider_name");
                    if (name.isEmpty() || b.indexOf(name) >= 0) continue;
                    if (b.length() > 0) b.append(", ");
                    b.append(name);
                }
                if (b.length() > 0) out.put(k[1], b.toString());
            }
        }
        return out;
    }

    /** A title's TMDb id found by name (and year when known), or "" (no key, no match). Call off the UI thread. */
    static String findId(Context c, boolean tv, String title, String year) {
        String key = key(c);
        if (key.isEmpty() || title.isEmpty()) return "";
        try {
            String q = (tv ? "search/tv?query=" : "search/movie?query=") + URLEncoder.encode(title, "UTF-8");
            JSONObject hit = year.isEmpty() ? null : pick(get(key, q + (tv ? "&first_air_date_year=" : "&year=") + year).optJSONArray("results"), title, year, tv);
            if (hit == null) hit = pick(get(key, q).optJSONArray("results"), title, year, tv);
            return hit == null ? "" : String.valueOf(hit.optInt("id"));
        } catch (Throwable e) { return ""; }
    }

    /** A TMDb image address at the given size (e.g. "w342", "w780"), or "" for none. */
    static String image(String path, String size) {
        return path == null || path.isEmpty() || "null".equals(path) ? "" : IMAGES + size + path;
    }

    static boolean works(String key) {
        try { return get(key, "configuration").has("images"); } catch (Exception e) { return false; }
    }

    /** Short keys are v3 "API keys" (api_key=); long ones are v4 "API Read Access Tokens" (Bearer). */
    private static JSONObject get(String key, String path) throws Exception {
        boolean bearer = key.length() > 40;
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection conn = Library.open(url, 15000);
        if (bearer) conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code == 404) throw new java.io.FileNotFoundException(path);
            if (code != 200) throw new java.io.IOException("TMDb answered " + code);
            return new JSONObject(read(conn.getInputStream()));
        } finally { conn.disconnect(); }
    }

    static String read(InputStream stream) throws java.io.IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    static void save(File cache, JSONObject meta) {
        try {
            File dir = cache.getParentFile();
            if (dir != null) dir.mkdirs();
            JSONObject o = new JSONObject().put("saved", System.currentTimeMillis());
            if (meta == null) o.put("miss", true); else o.put("meta", meta);
            try (OutputStream out = new FileOutputStream(cache)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ helpers

    private static void put(JSONObject o, String k, String v) {
        if (v == null) return;
        v = v.trim();
        if (v.isEmpty() || v.equals("null") || v.equals("[]")) return;
        try { o.put(k, v); } catch (Exception ignored) { }
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            String s = v instanceof JSONArray ? firstOf(v) : v == null ? "" : String.valueOf(v).trim();
            if (!s.isEmpty() && !s.equals("null") && !s.equals("0")) return s;
        }
        return "";
    }

    private static String firstOf(Object v) {
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) { String s = a.optString(i, "").trim(); if (!s.isEmpty()) return s; }
            return "";
        }
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static int firstInt(JSONArray a) { return a == null || a.length() == 0 ? 0 : a.optInt(0, 0); }

    static String yearOf(String date) {
        Matcher m = Pattern.compile("(?:19|20)\\d\\d").matcher(date == null ? "" : date);
        return m.find() ? m.group() : "";
    }

    /** "01:52:00" → 112, "112 min" → 112, "112" → 112. */
    static int minutes(String s) {
        if (s == null || s.isEmpty()) return 0;
        Matcher hms = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").matcher(s.trim());
        if (hms.find()) return Integer.parseInt(hms.group(1)) * 60 + Integer.parseInt(hms.group(2));
        Matcher n = Pattern.compile("\\d+").matcher(s);
        return n.find() ? Math.min(Integer.parseInt(n.group()), 999) : 0;
    }

    /** A 0–10 rating with one decimal, or "" for none. */
    static String rating(String s) {
        try {
            double d = Double.parseDouble(s.trim().replace(',', '.'));
            if (d <= 0 || d > 10) return "";
            return String.format(Locale.US, "%.1f", d);
        } catch (Exception e) { return ""; }
    }

    private static String names(JSONArray a, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0, n = 0; a != null && i < a.length() && n < max; i++) {
            JSONObject o = a.optJSONObject(i);
            String name = o == null ? "" : o.optString("name");
            if (name.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(name);
            n++;
        }
        return b.toString();
    }

    private static String certification(JSONObject t, boolean tv) {
        JSONObject box = t.optJSONObject(tv ? "content_ratings" : "release_dates");
        JSONArray list = box == null ? null : box.optJSONArray("results");
        String country = Locale.getDefault().getCountry();
        String found = "", us = "";
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            if (r == null) continue;
            String c = r.optString("iso_3166_1"), cert = "";
            if (tv) cert = r.optString("rating");
            else {
                JSONArray dates = r.optJSONArray("release_dates");
                for (int j = 0; dates != null && j < dates.length() && cert.isEmpty(); j++) {
                    JSONObject d = dates.optJSONObject(j);
                    if (d != null) cert = d.optString("certification").trim();
                }
            }
            if (cert.isEmpty()) continue;
            if (c.equals(country)) found = cert;
            if (c.equals("US")) us = cert;
        }
        return found.isEmpty() ? us : found;
    }

    private static String digits(String s) { return s != null && s.matches("\\d{1,9}") && !s.matches("0+") ? s : ""; }

    private static String url(String s) { return s != null && (s.startsWith("http://") || s.startsWith("https://")) ? s : ""; }
}
