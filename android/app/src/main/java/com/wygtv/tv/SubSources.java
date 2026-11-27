package com.wygtv.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Subtitles from outside the stream, for movies and episodes: searched online (Podnapisi needs nothing,
 * OpenSubtitles.com and SubDL need a free API key typed in Settings), loaded from a link, or from a file
 * on the TV or a USB drive. Each one is saved as a UTF-8 file in the app's cache and handed to the player.
 */
final class SubSources {
    private SubSources() { }

    static final String PREF_LANG = "subtitles_lang", PREF_SIZE = "subtitles_size";
    static final String PREF_OS_KEY = "opensubtitles_key", PREF_OS_USER = "opensubtitles_user", PREF_OS_PASS = "opensubtitles_pass";
    static final String PREF_SUBDL_KEY = "subdl_key";

    static final String PODNAPISI = "Podnapisi", OPENSUBTITLES = "OpenSubtitles", SUBDL = "SubDL";

    /** Subtitle languages offered in Settings; "" follows the TV's own language. */
    static final String[] LANG_CODES = {"", "en", "es", "fr", "de", "it", "pt", "nl", "pl", "ro", "el", "tr", "ru", "uk", "ar", "he", "sv", "da", "no", "fi", "hu", "cs", "hr", "sr", "bg", "hi", "vi", "th", "id", "ja", "ko", "zh"};

    static String[] langLabels() {
        String[] out = new String[LANG_CODES.length];
        out[0] = "Same as the TV (" + Locale.getDefault().getDisplayLanguage() + ")";
        for (int i = 1; i < out.length; i++) out[i] = new Locale(LANG_CODES[i]).getDisplayLanguage();
        return out;
    }

    /** Two-letter language for picking tracks and searching online. */
    static String lang(SharedPreferences prefs) {
        String l = prefs.getString(PREF_LANG, "");
        if (l == null || l.isEmpty()) l = Locale.getDefault().getLanguage();
        return l == null || l.isEmpty() ? "en" : l;
    }

    static String langName(String code) {
        String n = new Locale(code).getDisplayLanguage();
        return n.isEmpty() ? code : n;
    }

    /** Text size choices, as a fraction of the normal caption size. */
    static final String[] SIZE_KEYS = {"small", "normal", "large", "xlarge"};
    static final String[] SIZE_LABELS = {"Small", "Normal", "Large", "Extra large"};
    static final float[] SIZE_SCALE = {0.8f, 1f, 1.3f, 1.65f};

    static int sizeIndex(SharedPreferences prefs) {
        String k = prefs.getString(PREF_SIZE, "normal");
        for (int i = 0; i < SIZE_KEYS.length; i++) if (SIZE_KEYS[i].equals(k)) return i;
        return 1;
    }

    // ------------------------------------------------------------------ what to look for

    /** What is playing, as a subtitle search: title, year, and for episodes the season and episode numbers. */
    static final class Query {
        String title = "", year = "", season = "", episode = "";
        boolean series;

        String describe() {
            String s = title + (year.isEmpty() ? "" : " (" + year + ")");
            if (series && !season.isEmpty() && !episode.isEmpty()) s += String.format(Locale.US, "  S%02dE%02d", toInt(season), toInt(episode));
            return s;
        }
    }

    private static final Pattern SXXEYY = Pattern.compile("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b");
    private static final Pattern NXNN = Pattern.compile("\\b(\\d{1,2})x(\\d{2,3})\\b");

    /** Builds the search from the player's title, plus the series name and episode numbers when an episode is playing. */
    static Query queryFor(String type, String title, String seriesTitle, String season, String episode) {
        Query q = new Query();
        q.series = "series".equals(type);
        String raw = title == null ? "" : title;
        Matcher m = SXXEYY.matcher(raw);
        if (!m.find()) m = NXNN.matcher(raw);
        else m.reset();
        if (m.find()) {
            q.series = true;
            if (season == null || season.isEmpty()) season = m.group(1);
            if (episode == null || episode.isEmpty()) episode = m.group(2);
            if (seriesTitle == null || seriesTitle.isEmpty()) seriesTitle = raw.substring(0, m.start());
        }
        String[] clean = Tmdb.cleanTitle(q.series && seriesTitle != null && !seriesTitle.trim().isEmpty() ? seriesTitle : raw);
        q.title = clean[0].replaceAll("[\\s\\-:|•]+$", "").trim();
        q.year = clean[1];
        q.season = digits(season);
        q.episode = digits(episode);
        return q;
    }

    // ------------------------------------------------------------------ results

    static final class Result {
        String source = "", id = "", name = "", lang = "", url = "";
        int downloads;
        boolean hearingImpaired;
        String season = "", episode = "";

        String label() {
            String s = langName(lang) + "  •  " + (name.isEmpty() ? "subtitle" : name);
            if (hearingImpaired) s += "  (SDH)";
            if (downloads > 0) s += "  •  " + (downloads >= 1000 ? (downloads / 1000) + "k" : String.valueOf(downloads)) + " downloads";
            return s + "  •  " + source;
        }
    }

    /** The sources that can be used now: Podnapisi always, the others once their key is saved. */
    static List<String> sources(SharedPreferences prefs) {
        List<String> out = new ArrayList<>();
        if (!prefs.getString(PREF_OS_KEY, "").trim().isEmpty()) out.add(OPENSUBTITLES);
        if (!prefs.getString(PREF_SUBDL_KEY, "").trim().isEmpty()) out.add(SUBDL);
        out.add(PODNAPISI);
        return out;
    }

    /** Searches every usable source (off the UI thread). Problems with a source are added to {@code problems}. */
    static List<Result> search(SharedPreferences prefs, Query q, String lang, List<String> problems) {
        List<Result> all = new ArrayList<>();
        for (String s : sources(prefs)) {
            try {
                if (s.equals(OPENSUBTITLES)) all.addAll(openSubtitlesSearch(prefs, q, lang));
                else if (s.equals(SUBDL)) all.addAll(subdlSearch(prefs, q, lang));
                else all.addAll(podnapisiSearch(q, lang));
            } catch (Exception e) {
                problems.add(s + ": " + (e.getMessage() == null ? "no answer" : e.getMessage()));
            }
        }
        sort(all, lang);
        return all;
    }

    /** Wanted language first, then the most downloaded. */
    static void sort(List<Result> list, String lang) {
        Collections.sort(list, (x, y) -> {
            boolean xl = sameLang(x.lang, lang), yl = sameLang(y.lang, lang);
            if (xl != yl) return xl ? -1 : 1;
            return Integer.compare(y.downloads, x.downloads);
        });
    }

    static boolean sameLang(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.toLowerCase(Locale.US), y = b.toLowerCase(Locale.US);
        return x.equals(y) || x.startsWith(y + "-") || y.startsWith(x + "-");
    }

    // ------------------------------------------------------------------ OpenSubtitles.com

    private static final String OS_API = "https://api.opensubtitles.com/api/v1";
    private static String osToken = "", osBase = "", osTokenFor = "";

    static String openSubtitlesUrl(Query q, String lang) throws Exception {
        TreeMap<String, String> p = new TreeMap<>(); // the API wants its parameters sorted
        String l = lang.toLowerCase(Locale.US);
        p.put("languages", l.equals("pt") ? "pt-br,pt-pt" : l.equals("zh") ? "zh-cn,zh-tw" : l);
        p.put("query", q.title.toLowerCase(Locale.US));
        if (q.series) {
            p.put("type", "episode");
            if (!q.season.isEmpty()) p.put("season_number", String.valueOf(toInt(q.season)));
            if (!q.episode.isEmpty()) p.put("episode_number", String.valueOf(toInt(q.episode)));
        } else {
            p.put("type", "movie");
            if (!q.year.isEmpty()) p.put("year", q.year);
        }
        StringBuilder u = new StringBuilder(OS_API + "/subtitles?");
        for (java.util.Map.Entry<String, String> e : p.entrySet()) {
            if (u.charAt(u.length() - 1) != '?') u.append('&');
            u.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8").replace("+", "%20"));
        }
        return u.toString();
    }

    static List<Result> parseOpenSubtitles(JSONObject o) {
        List<Result> out = new ArrayList<>();
        JSONArray data = o == null ? null : o.optJSONArray("data");
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject a = data.optJSONObject(i) == null ? null : data.optJSONObject(i).optJSONObject("attributes");
            if (a == null) continue;
            JSONArray files = a.optJSONArray("files");
            JSONObject f = files == null || files.length() == 0 ? null : files.optJSONObject(0);
            if (f == null || f.optLong("file_id", 0) <= 0) continue;
            Result r = new Result();
            r.source = OPENSUBTITLES;
            r.id = String.valueOf(f.optLong("file_id"));
            r.lang = a.optString("language");
            r.name = a.optString("release");
            if (r.name.isEmpty()) r.name = f.optString("file_name");
            r.downloads = a.optInt("download_count", 0);
            r.hearingImpaired = a.optBoolean("hearing_impaired", false);
            out.add(r);
        }
        return out;
    }

    private static List<Result> openSubtitlesSearch(SharedPreferences prefs, Query q, String lang) throws Exception {
        HttpURLConnection c = openSubtitles(prefs, openSubtitlesUrl(q, lang));
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new java.io.IOException("the API key wasn't accepted (Settings > Manage player)");
            if (code != 200) throw new java.io.IOException("answered " + code);
            return parseOpenSubtitles(new JSONObject(Tmdb.read(c.getInputStream())));
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection openSubtitles(SharedPreferences prefs, String url) throws Exception {
        HttpURLConnection c = Library.open(url, 20000);
        c.setRequestProperty("Api-Key", prefs.getString(PREF_OS_KEY, "").trim());
        c.setRequestProperty("User-Agent", "WygTV v1.0");
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    /** Signs in when a username and password are saved (more downloads a day); otherwise downloads go without. */
    private static synchronized void openSubtitlesLogin(SharedPreferences prefs) {
        String user = prefs.getString(PREF_OS_USER, "").trim(), pass = prefs.getString(PREF_OS_PASS, "");
        String who = user + "|" + pass + "|" + prefs.getString(PREF_OS_KEY, "");
        if (user.isEmpty() || pass.isEmpty()) { osToken = ""; osBase = ""; osTokenFor = ""; return; }
        if (who.equals(osTokenFor) && !osToken.isEmpty()) return;
        try {
            JSONObject body = new JSONObject().put("username", user).put("password", pass);
            JSONObject o = post(prefs, OS_API + "/login", body, "");
            osToken = o.optString("token");
            String base = o.optString("base_url");
            osBase = base.isEmpty() ? "" : "https://" + base.replaceFirst("^https?://", "").replaceAll("/+$", "") + "/api/v1";
            osTokenFor = who;
        } catch (Exception e) { osToken = ""; osBase = ""; osTokenFor = ""; }
    }

    private static JSONObject post(SharedPreferences prefs, String url, JSONObject body, String token) throws Exception {
        HttpURLConnection c = openSubtitles(prefs, url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : Tmdb.read(in);
            if (code == 406 || code == 429) throw new java.io.IOException("today's OpenSubtitles download limit is used up. Add your OpenSubtitles username and password in Settings for more, or try another source");
            if (code == 401 || code == 403) throw new java.io.IOException("OpenSubtitles didn't accept the key or sign-in (Settings > Manage player)");
            if (code >= 400) throw new java.io.IOException("OpenSubtitles answered " + code);
            return new JSONObject(text);
        } finally { c.disconnect(); }
    }

    /** True when OpenSubtitles accepts the key (for Settings). */
    static boolean openSubtitlesWorks(SharedPreferences prefs) {
        try {
            HttpURLConnection c = openSubtitles(prefs, OS_API + "/infos/formats");
            try { return c.getResponseCode() == 200; } finally { c.disconnect(); }
        } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ SubDL

    static String subdlUrl(String key, Query q, String lang) throws Exception {
        StringBuilder u = new StringBuilder("https://api.subdl.com/api/v1/subtitles?api_key=").append(URLEncoder.encode(key, "UTF-8"))
                .append("&film_name=").append(URLEncoder.encode(q.title, "UTF-8"))
                .append("&languages=").append(lang.toUpperCase(Locale.US))
                .append("&subs_per_page=30&type=").append(q.series ? "tv" : "movie");
        if (q.series) {
            if (!q.season.isEmpty()) u.append("&season_number=").append(toInt(q.season));
            if (!q.episode.isEmpty()) u.append("&episode_number=").append(toInt(q.episode));
        } else if (!q.year.isEmpty()) u.append("&year=").append(q.year);
        return u.toString();
    }

    static List<Result> parseSubdl(JSONObject o) {
        List<Result> out = new ArrayList<>();
        JSONArray subs = o == null ? null : o.optJSONArray("subtitles");
        for (int i = 0; subs != null && i < subs.length(); i++) {
            JSONObject s = subs.optJSONObject(i);
            if (s == null || !s.optString("url").startsWith("/")) continue;
            Result r = new Result();
            r.source = SUBDL;
            r.url = "https://dl.subdl.com" + s.optString("url");
            r.id = s.optString("url");
            r.lang = s.optString("language").toLowerCase(Locale.US);
            r.name = s.optString("release_name");
            if (r.name.isEmpty()) r.name = s.optString("name");
            r.hearingImpaired = s.optBoolean("hi", false);
            r.season = s.optString("season");
            r.episode = s.optString("episode");
            out.add(r);
        }
        return out;
    }

    private static List<Result> subdlSearch(SharedPreferences prefs, Query q, String lang) throws Exception {
        HttpURLConnection c = Library.open(subdlUrl(prefs.getString(PREF_SUBDL_KEY, "").trim(), q, lang), 20000);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            JSONObject o = new JSONObject(in == null ? "{}" : Tmdb.read(in));
            if (!o.optBoolean("status", code == 200)) {
                String why = o.optString("error");
                if (why.toLowerCase(Locale.US).contains("not found") || why.toLowerCase(Locale.US).contains("can't find")) return new ArrayList<>();
                throw new java.io.IOException(why.isEmpty() ? "answered " + code : why);
            }
            return parseSubdl(o);
        } finally { c.disconnect(); }
    }

    // ------------------------------------------------------------------ Podnapisi

    static String podnapisiUrl(Query q, String lang) throws Exception {
        StringBuilder u = new StringBuilder("https://www.podnapisi.net/subtitles/search/old?sXML=1&sL=").append(URLEncoder.encode(lang, "UTF-8"))
                .append("&sK=").append(URLEncoder.encode(q.title, "UTF-8"));
        if (q.series) {
            if (!q.season.isEmpty()) u.append("&sTS=").append(toInt(q.season));
            if (!q.episode.isEmpty()) u.append("&sTE=").append(toInt(q.episode));
        } else if (!q.year.isEmpty()) u.append("&sY=").append(q.year);
        return u.toString();
    }

    private static final Pattern XML_SUB = Pattern.compile("(?s)<subtitle>(.*?)</subtitle>");

    static List<Result> parsePodnapisi(String xml) {
        List<Result> out = new ArrayList<>();
        if (xml == null) return out;
        Matcher m = XML_SUB.matcher(xml);
        while (m.find()) {
            String s = m.group(1);
            String pid = tag(s, "pid");
            if (pid.isEmpty()) continue;
            Result r = new Result();
            r.source = PODNAPISI;
            r.id = pid;
            r.url = "https://www.podnapisi.net/subtitles/" + pid + "/download?container=zip";
            r.lang = tag(s, "language");
            r.name = tag(s, "release").trim().replaceAll("\\s+", " / ");
            if (r.name.isEmpty()) r.name = tag(s, "title");
            String dl = tag(s, "downloads");
            r.downloads = dl.matches("\\d{1,9}") ? Integer.parseInt(dl) : 0;
            r.hearingImpaired = tag(s, "flags").contains("n");
            out.add(r);
        }
        return out;
    }

    private static String tag(String s, String name) {
        Matcher m = Pattern.compile("(?s)<" + name + ">(.*?)</" + name + ">").matcher(s);
        if (!m.find()) return "";
        String v = m.group(1).trim();
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) v = v.substring(9, v.length() - 3);
        return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    private static List<Result> podnapisiSearch(Query q, String lang) throws Exception {
        HttpURLConnection c = Library.open(podnapisiUrl(q, lang), 20000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("answered " + code);
            return parsePodnapisi(Tmdb.read(c.getInputStream()));
        } finally { c.disconnect(); }
    }

    // ------------------------------------------------------------------ downloading

    /** Downloads a search result and saves it as a subtitle file in the cache (off the UI thread). */
    static File download(Context c, SharedPreferences prefs, Result r, Query q) throws Exception {
        byte[] data;
        String nameHint;
        if (r.source.equals(OPENSUBTITLES)) {
            openSubtitlesLogin(prefs);
            String api = osBase.isEmpty() ? OS_API : osBase;
            JSONObject o = post(prefs, api + "/download", new JSONObject().put("file_id", Long.parseLong(r.id)).put("sub_format", "srt"), osToken);
            String link = o.optString("link");
            if (link.isEmpty()) throw new java.io.IOException(o.optString("message", "OpenSubtitles gave no download"));
            data = fetch(link);
            nameHint = o.optString("file_name", "subtitle.srt");
        } else {
            data = fetch(r.url);
            nameHint = r.url;
        }
        return save(c, data, nameHint, r.lang, q);
    }

    /** A subtitle (or a zip with one) from any link. */
    static File fromLink(Context c, String url) throws Exception {
        return save(c, fetch(url), url, "", null);
    }

    /** A subtitle file on the device, copied into the cache as UTF-8. */
    static File fromFile(Context c, File f, String lang) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) { return save(c, readAll(in, 8 << 20), f.getName(), lang, null); }
    }

    static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = Library.open(url, 30000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new java.io.IOException("the download answered " + code);
            return readAll(c.getInputStream(), 8 << 20);
        } finally { c.disconnect(); }
    }

    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new java.io.IOException("the file is too big for a subtitle");
        }
        return out.toByteArray();
    }

    /** Unzips when needed, picks the right file, fixes the text encoding and saves it with the right extension. */
    static File save(Context c, byte[] data, String nameHint, String lang, Query q) throws Exception {
        String name = nameHint == null ? "" : nameHint;
        if (isZip(data)) {
            String[] picked = new String[1];
            data = unzip(data, q, picked);
            if (data == null) throw new java.io.IOException("the download had no subtitle file in it");
            name = picked[0];
        }
        String ext = extensionOf(name);
        if (ext.isEmpty()) ext = sniff(data);
        if (ext.isEmpty()) throw new java.io.IOException("that isn't a subtitle file (.srt, .vtt, .ass or .ssa)");
        byte[] text = toUtf8(data, lang);
        File dir = new File(c.getCacheDir(), "subs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no room to save subtitles");
        File[] old = dir.listFiles();
        if (old != null && old.length > 30) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000) f.delete();
        File out = new File(dir, "sub-" + System.currentTimeMillis() + "." + ext);
        try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(text); }
        return out;
    }

    static boolean isZip(byte[] d) { return d != null && d.length > 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4; }

    /** The best subtitle in a zip: the episode asked for when it holds a whole season, .srt before other kinds, then the biggest. */
    static byte[] unzip(byte[] zip, Query q, String[] pickedName) throws java.io.IOException {
        byte[] best = null;
        int bestScore = -1;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String ext = extensionOf(e.getName());
                if (ext.isEmpty()) continue;
                byte[] d = readAll(z, 8 << 20);
                int score = (ext.equals("srt") ? 2 : 1) * 1_000_000 + Math.min(d.length, 999_999);
                if (q != null && q.series && !q.season.isEmpty() && !q.episode.isEmpty() && matchesEpisode(e.getName(), q.season, q.episode)) score += 10_000_000;
                if (score > bestScore) { bestScore = score; best = d; pickedName[0] = e.getName(); }
            }
        }
        return best;
    }

    static boolean matchesEpisode(String name, String season, String episode) {
        int s = toInt(season), ep = toInt(episode);
        Matcher m = Pattern.compile("(?i)S0*" + s + "\\s*E0*" + ep + "(?!\\d)|(?<!\\d)0*" + s + "x0*" + ep + "(?!\\d)").matcher(name);
        return m.find();
    }

    static String extensionOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
        for (String e : new String[]{"srt", "vtt", "ass", "ssa"}) if (n.endsWith("." + e)) return e;
        return "";
    }

    /** Guesses the kind of subtitle from its first lines when the name doesn't say. */
    static String sniff(byte[] d) {
        String head = new String(d, 0, Math.min(d.length, 2000), StandardCharsets.ISO_8859_1).replace("\r", "");
        if (head.startsWith("ï»¿")) head = head.substring(3);
        if (head.trim().startsWith("WEBVTT")) return "vtt";
        if (head.contains("[Script Info]")) return "ass";
        if (Pattern.compile("(?m)^\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->").matcher(head).find()) return "srt";
        return "";
    }

    /** Most subtitles online are UTF-8; older ones use the Windows code page of their language. The player wants UTF-8. */
    static byte[] toUtf8(byte[] d, String lang) {
        int start = d.length >= 3 && (d[0] & 0xff) == 0xEF && (d[1] & 0xff) == 0xBB && (d[2] & 0xff) == 0xBF ? 3 : 0;
        if (d.length >= 2 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xFE) return new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE).getBytes(StandardCharsets.UTF_8);
        if (d.length >= 2 && (d[0] & 0xff) == 0xFE && (d[1] & 0xff) == 0xFF) return new String(d, 2, d.length - 2, StandardCharsets.UTF_16BE).getBytes(StandardCharsets.UTF_8);
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(d, start, d.length - start));
            return start == 0 ? d : java.util.Arrays.copyOfRange(d, start, d.length);
        } catch (CharacterCodingException notUtf8) {
            Charset cs;
            try { cs = Charset.forName(codePage(lang)); } catch (Exception e) { cs = StandardCharsets.ISO_8859_1; }
            return new String(d, start, d.length - start, cs).getBytes(StandardCharsets.UTF_8);
        }
    }

    static String codePage(String lang) {
        String l = lang == null ? "" : lang.toLowerCase(Locale.US);
        if (l.length() > 2) l = l.substring(0, 2);
        switch (l) {
            case "ru": case "uk": case "bg": case "sr": case "mk": case "be": return "windows-1251";
            case "el": return "windows-1253";
            case "tr": return "windows-1254";
            case "he": return "windows-1255";
            case "ar": case "fa": return "windows-1256";
            case "pl": case "cs": case "sk": case "hu": case "ro": case "hr": case "sl": case "bs": return "windows-1250";
            case "vi": return "windows-1258";
            case "th": return "TIS-620";
            case "zh": return "GBK";
            case "ja": return "Shift_JIS";
            case "ko": return "EUC-KR";
            default: return "windows-1252";
        }
    }

    /** Subtitle files in the usual places on the TV and on USB drives, newest first. */
    static List<File> localFiles() {
        List<File> found = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        File ext = android.os.Environment.getExternalStorageDirectory();
        if (ext != null) roots.add(ext);
        File storage = new File("/storage");
        File[] vols = storage.listFiles();
        if (vols != null) for (File v : vols) if (v.isDirectory() && !v.getName().equals("emulated") && !v.getName().equals("self")) roots.add(v);
        File mnt = new File("/mnt/media_rw");
        File[] usb = mnt.listFiles();
        if (usb != null) Collections.addAll(roots, usb);
        for (File r : roots) scan(r, 0, found);
        Collections.sort(found, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        return found;
    }

    private static void scan(File dir, int depth, List<File> out) {
        if (out.size() >= 300 || depth > 3) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (out.size() >= 300) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) { if (!n.equals("Android")) scan(f, depth + 1, out); }
            else if (!extensionOf(n).isEmpty()) out.add(f);
        }
    }

    private static String digits(String s) {
        if (s == null) return "";
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() || d.length() > 4 ? "" : String.valueOf(Integer.parseInt(d));
    }

    static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
