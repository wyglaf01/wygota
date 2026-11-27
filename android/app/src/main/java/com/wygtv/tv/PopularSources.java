package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What's popular right now, from public sources that need no key or account, for the main screen's rows:
 * <ul>
 * <li>Movies: Rotten Tomatoes' "popular at home" list, else Apple's iTunes top movies chart.</li>
 * <li>Series: Rotten Tomatoes' popular TV list, else TVmaze's most popular shows airing now.</li>
 * </ul>
 * Each title comes back as {"_title", "_year", "_rating", "_overview", "_poster", "_backdrop", "_source"}.
 * The first source that answers is used; if one changes or goes away, the next takes over. Call off the UI thread.
 */
final class PopularSources {
    private PopularSources() { }

    static JSONArray movies(int max) {
        JSONArray got = rottenTomatoes("movies_at_home", max);
        if (got.length() == 0) got = apple(max);
        return got;
    }

    static JSONArray series(int max) {
        JSONArray got = rottenTomatoes("tv_series_browse", max);
        if (got.length() == 0) got = tvmaze(max);
        return got;
    }

    // ------------------------------------------------------------------ Rotten Tomatoes (public browse lists)

    /** Rotten Tomatoes' browse list sorted by popularity: "movies_at_home" or "tv_series_browse". */
    private static JSONArray rottenTomatoes(String list, int max) {
        JSONArray out = new JSONArray();
        try {
            JSONObject page = new JSONObject(Library.download("https://www.rottentomatoes.com/cnapi/browse/" + list + "/sort:popular", 20000));
            JSONObject grid = page.optJSONObject("grid");
            JSONArray items = grid == null ? null : grid.optJSONArray("list");
            for (int i = 0; items != null && i < items.length() && out.length() < max; i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || it.optString("title").isEmpty()) continue;
                String poster = it.optString("posterUri");
                // The poster address is a small resized copy of an inner image address: use the inner (full-size) one.
                int inner = poster.indexOf("/v2/https://");
                if (inner >= 0) poster = poster.substring(inner + 4);
                if (poster.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("_title", it.optString("title"));
                // The page address often ends in the year (e.g. /tv/the_body_2026); it helps find the right title.
                java.util.regex.Matcher y = java.util.regex.Pattern.compile("_((?:19|20)\\d\\d)$").matcher(it.optString("mediaUrl"));
                o.put("_year", y.find() ? y.group(1) : "");
                String critics = score(it.optJSONObject("criticsScore")), audience = score(it.optJSONObject("audienceScore"));
                StringBuilder r = new StringBuilder();
                if (!critics.isEmpty()) r.append("Critics ").append(critics);
                if (!audience.isEmpty()) r.append(r.length() > 0 ? "  •  " : "").append("Audience ").append(audience);
                o.put("_rating", r.toString());
                o.put("_overview", it.optString("releaseDateText"));
                o.put("_poster", poster);
                o.put("_backdrop", "");
                o.put("_source", "Rotten Tomatoes");
                out.put(o);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String score(JSONObject s) {
        if (s == null) return "";
        String p = s.optString("scorePercent");
        if (p.isEmpty() && !s.optString("score").isEmpty()) p = s.optString("score") + "%";
        return p;
    }

    // ------------------------------------------------------------------ Apple iTunes top movies (public chart)

    private static JSONArray apple(int max) {
        JSONArray out = new JSONArray();
        try {
            String country = java.util.Locale.getDefault().getCountry();
            if (country == null || country.length() != 2) country = "us";
            JSONObject feed = new JSONObject(Library.download("https://itunes.apple.com/" + country.toLowerCase(java.util.Locale.ROOT)
                    + "/rss/topmovies/limit=" + Math.min(100, max) + "/json", 20000)).optJSONObject("feed");
            JSONArray entries = feed == null ? null : feed.optJSONArray("entry");
            for (int i = 0; entries != null && i < entries.length() && out.length() < max; i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e == null) continue;
                String[] t = Tmdb.cleanTitle(label(e, "im:name"));
                JSONArray imgs = e.optJSONArray("im:image");
                String poster = imgs == null || imgs.length() == 0 ? "" : imgs.optJSONObject(imgs.length() - 1).optString("label");
                poster = poster.replaceAll("/\\d+x\\d+(bb)?\\.(jpg|png)$", "/400x600bb.jpg"); // the chart lists small posters; ask for a bigger one
                if (t[0].isEmpty() || poster.isEmpty()) continue;
                String date = label(e, "im:releaseDate");
                JSONObject o = new JSONObject();
                o.put("_title", t[0]);
                o.put("_year", !t[1].isEmpty() ? t[1] : date.length() >= 4 ? date.substring(0, 4) : "");
                JSONObject cat = e.optJSONObject("category");
                JSONObject attrs = cat == null ? null : cat.optJSONObject("attributes");
                o.put("_rating", attrs == null ? "" : attrs.optString("label"));
                o.put("_overview", label(e, "summary"));
                o.put("_poster", poster);
                o.put("_backdrop", "");
                o.put("_source", "Apple top movies");
                out.put(o);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String label(JSONObject e, String key) {
        JSONObject v = e.optJSONObject(key);
        return v == null ? "" : v.optString("label");
    }

    // ------------------------------------------------------------------ TVmaze (public schedule)

    /** Shows with new episodes in the last few days (streaming and TV), most popular (TVmaze "weight") first. */
    private static JSONArray tvmaze(int max) {
        JSONArray out = new JSONArray();
        java.util.Map<Integer, JSONObject> shows = new java.util.HashMap<>();
        String country = java.util.Locale.getDefault().getCountry();
        if (country == null || country.length() != 2) country = "US";
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        long now = System.currentTimeMillis();
        for (int d = 0; d < 3; d++) {
            String date = day.format(new java.util.Date(now - d * 86_400_000L));
            for (String path : new String[]{"schedule/web?date=" + date, "schedule?country=" + country + "&date=" + date}) {
                try {
                    JSONArray eps = new JSONArray(Library.download("https://api.tvmaze.com/" + path, 20000));
                    for (int i = 0; i < eps.length(); i++) {
                        JSONObject ep = eps.optJSONObject(i);
                        JSONObject emb = ep == null ? null : ep.optJSONObject("_embedded");
                        JSONObject show = emb != null ? emb.optJSONObject("show") : ep == null ? null : ep.optJSONObject("show");
                        if (show == null) continue;
                        String type = show.optString("type");
                        if (type.equals("News") || type.equals("Talk Show") || type.equals("Sports")) continue; // not what people pick to watch
                        JSONObject img = show.optJSONObject("image");
                        if (img == null || img.optString("original").isEmpty()) continue;
                        shows.put(show.optInt("id"), show);
                    }
                } catch (Throwable ignored) { }
            }
        }
        List<JSONObject> list = new ArrayList<>(shows.values());
        Collections.sort(list, (x, y) -> Integer.compare(y.optInt("weight"), x.optInt("weight")));
        for (int i = 0; i < list.size() && out.length() < max; i++) {
            JSONObject s = list.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("_title", s.optString("name"));
                String premiered = s.optString("premiered");
                o.put("_year", premiered.length() >= 4 ? premiered.substring(0, 4) : "");
                JSONObject rating = s.optJSONObject("rating");
                double avg = rating == null ? 0 : rating.optDouble("average", 0);
                o.put("_rating", avg > 0 ? String.format(java.util.Locale.US, "★ %.1f", avg) : "");
                o.put("_overview", s.optString("summary").replaceAll("<[^>]*>", "").trim());
                JSONObject img = s.optJSONObject("image");
                o.put("_poster", img.optString("medium", img.optString("original")));
                o.put("_backdrop", img.optString("original"));
                o.put("_source", "TVmaze");
                out.put(o);
            } catch (Throwable ignored) { }
        }
        return out;
    }
}
