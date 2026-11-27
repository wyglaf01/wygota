package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legacy Tablo network DVRs (the black models sold 2013–2023: Tablo DUAL, QUAD, LITE, DUAL 64GB…), through the
 * local REST server every one of them runs on port 8885. A Tablo is saved like an HDHomeRun tuner: as an M3U
 * playlist (its link is http://IP:8885/guide/channels) shown in Live TV as its own "📡 Tablo" category.
 *
 * A Tablo has no fixed stream link per channel: each time a channel is tuned the app asks the Tablo for a fresh
 * HLS link (POST /guide/channels/ID/watch). So the playlist holds a stand-in link per channel,
 * http://IP:8885/guide/channels/ID/watch.m3u8, and the player swaps it for the real one as it opens it
 * (see {@link #resolveWatch} and Players).
 *
 * The TV guide comes from the Tablo too: it keeps its own program guide (GET /guide/airings, then /batch for the
 * details), read into the same form as the HDHomeRun guide (see {@link #loadGuide}).
 *
 * The white 4th Gen Tablo has no open local API (it only talks to Tablo's own apps), so it isn't handled here:
 * {@link TabloApp} puts a button in the menus that opens the Tablo app instead.
 *
 * Plain Java (no Android classes), so the parsing is unit tested on the JVM.
 */
final class Tablo {
    /** Port of the Tablo's REST server. */
    static final int PORT = 8885;
    /** Tablo's cloud lookup: the Tablos that share this network's public address, with their private IPs. */
    static final String CLOUD_DISCOVER = "https://api.tablotv.com/assocserver/getipinfo/";
    static final String DEFAULT_NAME = "Tablo";
    /** Shown when the Tablo answers but refuses other apps (newer firmware locked the API on some units). */
    static final String LOCKED = "This Tablo answered but refuses other apps (its firmware locked the API that WygTV uses). "
            + "Its channels can still be watched in the Tablo app: turn on \"White 4th Gen Tablo\" in the Tablo settings to put a button for it in the menus.";

    private static final Pattern WATCH = Pattern.compile("^/guide/channels/(\\d+)/watch\\.m3u8$");
    /** How long a tuned channel's real link is reused (the player re-reads it every few seconds while playing). */
    static final long REUSE_MS = 30_000;

    private Tablo() { }

    /** One Tablo, from the cloud lookup and its /server/info. */
    static final class Device {
        String id = "", name = "", model = "", base = "";
        int tuners;

        /** "Tablo QUAD  •  192.168.1.60  •  4 tuners" */
        String summary() {
            StringBuilder s = new StringBuilder(!model.isEmpty() ? model : name.isEmpty() ? "Tablo" : name);
            if (!name.isEmpty() && !model.isEmpty() && !name.equalsIgnoreCase(model) && !name.equalsIgnoreCase("Tablo")) s.append(" “").append(name).append("”");
            String host = Hdhr.hostOf(base);
            if (!host.isEmpty()) s.append("  •  ").append(host);
            if (tuners > 0) s.append("  •  ").append(tuners).append(tuners == 1 ? " tuner" : " tuners");
            return s.toString();
        }
    }

    /** The Tablo refused the request (HTTP 401/403 or an "unauthorized" answer). */
    static final class LockedException extends Exception {
        LockedException() { super(LOCKED); }
    }

    // ------------------------------------------------------------------ addresses

    /** "192.168.1.60", "192.168.1.60:8885", "http://192.168.1.60/" → "http://192.168.1.60:8885". */
    static String baseFromInput(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)https?://.*")) s = "http://" + s;
        try {
            URI u = new URI(s);
            if (u.getHost() == null) return "";
            int port = u.getPort() < 0 || u.getPort() == 80 ? PORT : u.getPort();
            return "http://" + u.getHost() + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }

    /** The playlist link a Tablo is saved under. */
    static String channelsUrl(String base) { return base.replaceAll("/+$", "") + "/guide/channels"; }

    /** True for a saved Tablo's playlist link (http://IP:8885/guide/channels). */
    static boolean isChannelsUrl(String url) {
        if (url == null) return false;
        try {
            URI u = new URI(url.trim());
            String path = u.getRawPath();
            return u.getPort() == PORT && path != null && path.replaceAll("/+$", "").equals("/guide/channels");
        } catch (Exception e) {
            return false;
        }
    }

    /** "http://192.168.1.60:8885/guide/channels" → "http://192.168.1.60:8885". */
    static String baseOf(String url) {
        try {
            URI u = new URI(url.trim());
            return u.getScheme() + "://" + u.getRawAuthority();
        } catch (Exception e) {
            return "";
        }
    }

    /** The stand-in link a channel has in the playlist. */
    static String watchUrl(String base, String channelId) { return base.replaceAll("/+$", "") + "/guide/channels/" + channelId + "/watch.m3u8"; }

    /** True for a channel's stand-in link (swapped for a real stream link when it plays). */
    static boolean isWatchUrl(String url) {
        if (url == null) return false;
        try { String p = new URI(url.trim()).getRawPath(); return p != null && WATCH.matcher(p).matches(); } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ parsing

    /** The cloud answer: {"success":true,"cpes":[{"serverid":"SID_…","name":"Tablo","private_ip":"192.168.1.60",…}]}. */
    static List<Device> parseCloud(String json) {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray list = new JSONObject(json.trim()).optJSONArray("cpes");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null || o.optString("private_ip").isEmpty()) continue;
                Device d = new Device();
                d.id = o.optString("serverid");
                d.name = o.optString("name");
                d.base = baseFromInput(o.optString("private_ip"));
                if (!d.base.isEmpty()) out.add(d);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** /server/info: {"server_id":"SID_…","name":"Tablo","model":{"name":"Tablo QUAD","tuners":4,…},…}. */
    static Device parseServerInfo(String json, String base) throws Exception {
        JSONObject o = new JSONObject(json.trim());
        if (isRefusal(o)) throw new LockedException();
        Device d = new Device();
        d.id = o.optString("server_id");
        d.name = o.optString("name");
        JSONObject m = o.optJSONObject("model");
        if (m != null) { d.model = m.optString("name"); d.tuners = m.optInt("tuners", 0); }
        d.base = base;
        if (d.id.isEmpty() && d.name.isEmpty() && m == null) throw new IllegalStateException("not a Tablo");
        return d;
    }

    /** {"error":{"code":"unauthorized",…}} */
    static boolean isRefusal(JSONObject o) {
        JSONObject e = o == null ? null : o.optJSONObject("error");
        if (e == null) return false;
        String code = e.optString("code").toLowerCase(Locale.ROOT);
        return code.contains("unauthorized") || code.contains("forbidden") || e.optString("description").toLowerCase(Locale.ROOT).contains("authentication");
    }

    /** /guide/channels: ["/guide/channels/767579", …] → the channel paths. */
    static List<String> parseChannelPaths(String json) throws Exception {
        String t = json.trim();
        if (t.startsWith("{")) { if (isRefusal(new JSONObject(t))) throw new LockedException(); throw new IllegalStateException("no channel list"); }
        JSONArray list = new JSONArray(t);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) { String p = list.optString(i); if (p.startsWith("/guide/channels/")) out.add(p); }
        return out;
    }

    /**
     * Channel details (the /batch answer, path → {"object_id":…,"channel":{"call_sign":"WNBC","major":4,"minor":1,
     * "network":"NBC","resolution":"hd_1080"}}) into M3U text, grouped by main channel number like an HDHomeRun.
     */
    static String channelsToM3u(String base, List<String> order, JSONObject details) {
        List<JSONObject> chans = new ArrayList<>();
        for (String path : order) {
            JSONObject o = details.optJSONObject(path);
            if (o == null || o.optJSONObject("channel") == null) continue;
            chans.add(o);
        }
        java.util.Collections.sort(chans, (x, y) -> {
            JSONObject a = x.optJSONObject("channel"), b = y.optJSONObject("channel");
            int c = Integer.compare(a.optInt("major"), b.optInt("major"));
            return c != 0 ? c : Integer.compare(a.optInt("minor"), b.optInt("minor"));
        });
        Map<String, String> groupNames = new LinkedHashMap<>();
        for (JSONObject o : chans) {
            JSONObject c = o.optJSONObject("channel");
            String major = String.valueOf(c.optInt("major"));
            if (!groupNames.containsKey(major)) groupNames.put(major, Hdhr.station(callSign(c)));
        }
        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        for (JSONObject o : chans) {
            JSONObject c = o.optJSONObject("channel");
            String id = o.has("object_id") ? String.valueOf(o.optLong("object_id")) : lastPart(o.optString("path"));
            if (id.isEmpty()) continue;
            String major = String.valueOf(c.optInt("major"));
            String number = c.has("minor") ? major + "." + c.optInt("minor") : major;
            String call = callSign(c);
            String group = "Channel " + major + (groupNames.get(major).isEmpty() ? "" : "  ·  " + groupNames.get(major));
            String title = (number + "  " + call).trim();
            if (c.optString("resolution").toLowerCase(Locale.ROOT).startsWith("hd")) title += "  HD";
            String network = c.optString("network").trim();
            if (!network.isEmpty() && !network.equalsIgnoreCase("null") && !call.toUpperCase(Locale.ROOT).contains(network.toUpperCase(Locale.ROOT))) title += "  (" + network + ")";
            m3u.append("#EXTINF:-1 tvg-id=\"").append(attr(number)).append("\" tvg-chno=\"").append(attr(number))
                    .append("\" tvg-name=\"").append(attr(call.isEmpty() ? number : call)).append("\" group-title=\"").append(attr(group))
                    .append("\",").append(title.replace('\n', ' ')).append('\n')
                    .append(watchUrl(base, id)).append('\n');
        }
        return m3u.toString();
    }

    private static String callSign(JSONObject c) {
        String s = c.optString("call_sign").trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? c.optString("call_sign_src").trim() : s;
    }

    private static String lastPart(String path) {
        String p = path == null ? "" : path.replaceAll("/+$", "");
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(i + 1).replaceAll("\\D", "");
    }

    private static String attr(String v) { return v.replace('"', '\'').replace('\n', ' '); }

    /** The watch answer: {"token":…,"playlist_url":"http://192.168.1.60/stream/pl.m3u8?…",…} → the stream link. */
    static String parseWatch(String json, String base) throws Exception {
        JSONObject o = new JSONObject(json.trim());
        if (isRefusal(o)) throw new LockedException();
        String url = o.optString("playlist_url").trim();
        if (url.isEmpty()) {
            JSONObject e = o.optJSONObject("error");
            throw new IllegalStateException(e != null && !e.optString("description").isEmpty() ? e.optString("description") : "the Tablo gave no stream link");
        }
        if (url.startsWith("/")) url = "http://" + Hdhr.hostOf(base) + url;
        return url;
    }

    // ------------------------------------------------------------------ program guide

    /** "4.1" for a channel record ({"channel":{"major":4,"minor":1,…}}), "" when it has no number. */
    static String numberOf(JSONObject channelRecord) {
        JSONObject c = channelRecord == null ? null : channelRecord.optJSONObject("channel");
        if (c == null || !c.has("major")) return "";
        return c.has("minor") ? c.optInt("major") + "." + c.optInt("minor") : String.valueOf(c.optInt("major"));
    }

    /** "2023-01-03T01:00Z", "2023-01-03T01:00:30Z" or with an offset → ms since 1970; -1 when unreadable. */
    static long parseTime(String s) {
        if (s == null || s.trim().isEmpty()) return -1;
        String t = s.trim();
        for (String f : new String[]{"yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mmX", "yyyy-MM-dd'T'HH:mm:ss.SSSX"}) {
            try {
                java.text.SimpleDateFormat p = new java.text.SimpleDateFormat(f, Locale.ROOT);
                p.setLenient(false);
                java.text.ParsePosition pos = new java.text.ParsePosition(0);
                java.util.Date d = p.parse(t, pos);
                if (d != null && pos.getIndex() == t.length()) return d.getTime();
            } catch (Exception ignored) { }
        }
        return -1;
    }

    /**
     * Adds one airing record (an episode, movie airing, sports event or program airing, each with an
     * "airing_details" block) to {@code into}, keyed by channel number like the HDHomeRun guide.
     * {@code numbers} maps a channel's path ("/guide/channels/767579") to its number, for records without an
     * embedded channel. Returns false when the record isn't a usable airing.
     */
    static boolean addAiring(JSONObject rec, Map<String, String> numbers, Map<String, List<Xmltv.Program>> into) {
        JSONObject ad = rec == null ? null : rec.optJSONObject("airing_details");
        if (ad == null) return false;
        String number = numberOf(ad.optJSONObject("channel"));
        if (number.isEmpty()) {
            String path = ad.optString("channel_path");
            if (path.isEmpty() && ad.optJSONObject("channel") != null) path = ad.optJSONObject("channel").optString("path");
            String n = numbers.get(path);
            number = n == null ? "" : n;
        }
        long start = parseTime(ad.optString("datetime"));
        long dur = ad.optLong("duration", 0);
        if (number.isEmpty() || start <= 0 || dur <= 0) return false;
        String title = ad.optString("show_title").trim(), episode = "", desc = "";
        for (String k : new String[]{"episode", "event", "program", "movie_airing", "movie"}) {
            JSONObject o = rec.optJSONObject(k);
            if (o == null) continue;
            if (episode.isEmpty() && k.equals("episode")) episode = clean(o.optString("title"));
            if (episode.isEmpty() && k.equals("event")) episode = clean(o.optString("title"));
            if (desc.isEmpty()) desc = clean(o.optString("description"));
            if (title.isEmpty()) title = clean(o.optString("title"));
        }
        if (episode.equals(title)) episode = "";
        if (!episode.isEmpty()) desc = desc.isEmpty() ? episode : episode + " — " + desc;
        List<Xmltv.Program> list = into.get(number);
        if (list == null) { list = new ArrayList<>(); into.put(number, list); }
        for (Xmltv.Program have : list) if (have.start == start) return true; // the same airing twice
        list.add(new Xmltv.Program(start, start + dur * 1000, title.isEmpty() ? "Program" : title, desc));
        return true;
    }

    private static String clean(String s) { return s == null || s.equals("null") ? "" : s.trim(); }

    /** /guide/airings: ["/guide/series/episodes/123", "/guide/movies/airings/456", …]. */
    static List<String> parseAiringPaths(String json) throws Exception {
        String t = json.trim();
        if (t.startsWith("{")) { if (isRefusal(new JSONObject(t))) throw new LockedException(); throw new IllegalStateException("no airings"); }
        JSONArray list = new JSONArray(t);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) { String p = list.optString(i); if (p.startsWith("/")) out.add(p); }
        return out;
    }

    /** POSTs paths to /batch, 50 at a time, and hands each record to {@code each}. */
    private interface Each { void take(String path, JSONObject rec) throws Exception; }

    private static void batch(String base, List<String> paths, Each each) throws Exception {
        for (int i = 0; i < paths.size(); i += 50) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            JSONArray b = new JSONArray();
            for (int j = i; j < Math.min(paths.size(), i + 50); j++) b.put(paths.get(j));
            JSONObject got = new JSONObject(http("POST", base + "/batch", b.toString(), 30000).trim());
            if (isRefusal(got)) throw new LockedException();
            for (java.util.Iterator<String> k = got.keys(); k.hasNext(); ) { String key = k.next(); each.take(key, got.optJSONObject(key)); }
        }
    }

    /**
     * The Tablo's own program guide: about {@code hours} ahead for every channel, keyed by channel number
     * ("4.1"), sorted by start. Programmes that already ended are left out. Blocking: call off the UI thread.
     */
    static Map<String, List<Xmltv.Program>> loadGuide(String base, int hours) throws Exception {
        Map<String, String> numbers = new HashMap<>();
        batch(base, parseChannelPaths(http("GET", channelsUrl(base), null, 20000)), (path, rec) -> {
            String n = numberOf(rec);
            if (!n.isEmpty()) numbers.put(path, n);
        });
        Map<String, List<Xmltv.Program>> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis(), until = now + hours * 3600_000L;
        Map<String, List<Xmltv.Program>> all = new LinkedHashMap<>();
        batch(base, parseAiringPaths(http("GET", base + "/guide/airings", null, 30000)), (path, rec) -> addAiring(rec, numbers, all));
        for (Map.Entry<String, List<Xmltv.Program>> e : all.entrySet()) {
            List<Xmltv.Program> keep = new ArrayList<>();
            for (Xmltv.Program p : e.getValue()) if (p.stop > now && p.start < until) keep.add(p);
            if (keep.isEmpty()) continue;
            java.util.Collections.sort(keep, (x, y) -> Long.compare(x.start, y.start));
            out.put(e.getKey(), keep);
        }
        return out;
    }

    // ------------------------------------------------------------------ network (blocking: call off the UI thread)

    /** GET or POST (with a JSON body) to the Tablo. 401/403 throw {@link LockedException}. */
    static String http(String method, String url, String body, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "WygTV/1.0 (Android TV)");
        c.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream out = c.getOutputStream()) { out.write(b); }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new LockedException();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) try (InputStream i = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = i.read(buf)) != -1) out.write(buf, 0, n);
                text = out.toString("UTF-8");
            }
            if (code >= 400) {
                try { if (isRefusal(new JSONObject(text.trim()))) throw new LockedException(); } catch (LockedException e) { throw e; } catch (Exception ignored) { }
                throw new IllegalStateException("the Tablo answered HTTP " + code);
            }
            return text;
        } finally { c.disconnect(); }
    }

    /** Tablos on this network, from Tablo's cloud lookup, each confirmed with its /server/info (needs internet). */
    static List<Device> discoverAll() {
        List<Device> out = new ArrayList<>();
        List<Device> cloud;
        try { cloud = parseCloud(http("GET", CLOUD_DISCOVER, null, 8000)); } catch (Exception e) { cloud = new ArrayList<>(); }
        for (Device d : cloud) {
            try {
                Device full = describe(d.base);
                if (full.name.isEmpty()) full.name = d.name;
                out.add(full);
            } catch (LockedException e) {
                out.add(d); // still list it: adding it explains the lock
            } catch (Exception ignored) { /* offline or another network */ }
        }
        return out;
    }

    /** Reads the Tablo's /server/info. Throws when there is no legacy Tablo at that address. */
    static Device describe(String base) throws Exception {
        return parseServerInfo(http("GET", base + "/server/info", null, 8000), base);
    }

    /** The playlist text for a saved Tablo (its channels, with stand-in links). */
    static String playlistText(String channelsUrl) throws Exception {
        String base = baseOf(channelsUrl);
        List<String> paths = parseChannelPaths(http("GET", channelsUrl, null, 20000));
        JSONObject details = new JSONObject();
        batch(base, paths, (path, rec) -> { if (rec != null) details.put(path, rec); });
        return channelsToM3u(base, paths, details);
    }

    /** Stand-in link → {real link, last used (ms)}. */
    private static final Map<String, Object[]> tuned = new HashMap<>();

    /**
     * The real stream link for a channel's stand-in link: asks the Tablo to tune it, or reuses the link while
     * the player keeps re-reading it (live HLS re-reads its playlist every few seconds). Blocking.
     */
    static String resolveWatch(String standIn) throws Exception {
        long now = System.currentTimeMillis();
        synchronized (tuned) {
            Object[] have = tuned.get(standIn);
            if (have != null && now - (Long) have[1] < REUSE_MS) { have[1] = now; return (String) have[0]; }
        }
        Matcher m = WATCH.matcher(new URI(standIn).getRawPath());
        if (!m.matches()) throw new IllegalArgumentException("not a Tablo channel");
        String base = baseOf(standIn);
        String real = parseWatch(http("POST", base + "/guide/channels/" + m.group(1) + "/watch", "{}", 20000), base);
        synchronized (tuned) {
            // Forget links nothing is playing any more (an iterator, not removeIf: Android 6 has no removeIf).
            for (java.util.Iterator<Map.Entry<String, Object[]>> it = tuned.entrySet().iterator(); it.hasNext(); )
                if (now - (Long) it.next().getValue()[1] >= REUSE_MS) it.remove();
            tuned.put(standIn, new Object[]{real, now});
        }
        return real;
    }
}
