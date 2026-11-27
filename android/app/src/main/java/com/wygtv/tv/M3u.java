package com.wygtv.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java M3U / M3U8 playlist reader (no Android classes, so it is unit tested on the JVM).
 * Each channel gets a stable id built from its stream URL, so favorites, hidden channels and
 * "recently watched" keep working after the playlist is refreshed.
 */
final class M3u {
    static final String UNGROUPED = "Ungrouped";

    static final class Channel {
        final String id, name, url, group, logo, tvgId, tvgName, number;
        Channel(String id, String name, String url, String group, String logo, String tvgId, String tvgName, String number) {
            this.id = id; this.name = name; this.url = url; this.group = group;
            this.logo = logo; this.tvgId = tvgId; this.tvgName = tvgName; this.number = number;
        }
    }

    static final class Playlist {
        final List<Channel> channels = new ArrayList<>();
        /** Group names in the order they first appear. */
        final List<String> groups = new ArrayList<>();
        final Map<String, Channel> byId = new HashMap<>();
        /** The guide address the playlist itself advertises (url-tvg / x-tvg-url), or "". */
        String guideUrl = "";
    }

    private static final char BOM = (char) 0xFEFF;
    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    static Playlist parse(String text) {
        Playlist p = new Playlist();
        if (text == null) return p;
        if (!text.isEmpty() && text.charAt(0) == BOM) text = text.substring(1);
        Map<String, String> pending = null;
        String pendingName = null, pendingGroup = null;
        Map<String, Integer> seen = new HashMap<>();
        LinkedHashMap<String, Boolean> groups = new LinkedHashMap<>();
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.regionMatches(true, 0, "#EXTM3U", 0, 7)) {
                Map<String, String> head = attributes(line);
                String guide = first(head, "url-tvg", "x-tvg-url", "tvg-url");
                if (!guide.isEmpty()) p.guideUrl = guide.split(",")[0].trim();
                continue;
            }
            if (line.regionMatches(true, 0, "#EXTINF", 0, 7)) {
                pending = attributes(line);
                pendingName = displayName(line);
                pendingGroup = null;
                continue;
            }
            if (line.regionMatches(true, 0, "#EXTGRP:", 0, 8)) { pendingGroup = line.substring(8).trim(); continue; }
            if (line.startsWith("#")) continue;
            // A stream URL line.
            Map<String, String> a = pending == null ? new HashMap<>() : pending;
            String name = pendingName == null || pendingName.isEmpty() ? first(a, "tvg-name") : pendingName;
            if (name.isEmpty()) name = line;
            String group = first(a, "group-title");
            if (group.isEmpty() && pendingGroup != null) group = pendingGroup;
            if (group.isEmpty()) group = UNGROUPED;
            String base = idFor(line);
            Integer before = seen.get(base);
            int count = before == null ? 1 : before + 1;
            seen.put(base, count);
            String id = count == 1 ? base : base + "-" + count;
            Channel c = new Channel(id, name, line, group, first(a, "tvg-logo", "logo"), first(a, "tvg-id"), first(a, "tvg-name"),
                    first(a, "tvg-chno", "channel-number", "tvg-num"));
            p.channels.add(c);
            p.byId.put(id, c);
            groups.put(group, Boolean.TRUE);
            pending = null; pendingName = null; pendingGroup = null;
        }
        p.groups.addAll(groups.keySet());
        return p;
    }

    static Map<String, String> attributes(String line) {
        Map<String, String> out = new HashMap<>();
        Matcher m = ATTR.matcher(line);
        while (m.find()) out.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim());
        return out;
    }

    /** The title after the last comma that is outside quotes: #EXTINF:-1 tvg-name="a,b",Channel Name */
    static String displayName(String line) {
        boolean quoted = false;
        int comma = -1;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) { comma = i; break; }
        }
        return comma < 0 ? "" : line.substring(comma + 1).trim();
    }

    private static String first(Map<String, String> a, String... keys) {
        for (String k : keys) { String v = a.get(k); if (v != null && !v.isEmpty()) return v; }
        return "";
    }

    /** 64-bit FNV-1a of the URL, as hex: short, stable and practically collision free. */
    static String idFor(String url) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < url.length(); i++) { h ^= url.charAt(i); h *= 0x100000001b3L; }
        return "m" + Long.toHexString(h);
    }

    /** True when the text looks like an M3U playlist rather than an error page. */
    static boolean looksLikePlaylist(String text) {
        if (text == null) return false;
        String t = !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
        t = t.trim();
        return t.regionMatches(true, 0, "#EXTM3U", 0, 7) || t.contains("#EXTINF");
    }

    /**
     * An Xtream "get.php?username=..&password=..&type=m3u" link carries a full Xtream login.
     * Returns {server, username, password} or null.
     */
    static String[] xtreamLogin(String link) {
        try {
            URI u = new URI(link.trim());
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            if (u.getHost() == null || !path.toLowerCase(Locale.ROOT).endsWith("/get.php") || u.getRawQuery() == null) return null;
            String user = null, pass = null;
            for (String part : u.getRawQuery().split("&")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq), v = java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                if (k.equals("username")) user = v;
                else if (k.equals("password")) pass = v;
            }
            if (user == null || pass == null || user.isEmpty() || pass.isEmpty()) return null;
            String dir = path.substring(0, path.length() - "/get.php".length());
            String server = u.getScheme() + "://" + u.getRawAuthority() + dir;
            return new String[]{server, user, pass};
        } catch (Exception e) {
            return null;
        }
    }
}
