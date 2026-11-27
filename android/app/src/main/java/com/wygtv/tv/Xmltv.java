package com.wygtv.tv;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

import org.xmlpull.v1.XmlPullParser;

/**
 * Reads an XMLTV guide (plain or .gz), keeping only programmes near "now" so even large
 * guides fit in memory. Used for M3U playlists and for a custom guide source in Settings.
 */
final class Xmltv {
    static final class Program {
        final long start, stop; final String title, desc;
        Program(long start, long stop, String title, String desc) { this.start = start; this.stop = stop; this.title = title; this.desc = desc; }
    }

    static final class Guide {
        final Map<String, List<Program>> byChannel = new HashMap<>();
        /** Lower-cased display names and ids, to match channels that have no tvg-id. */
        final Map<String, String> byName = new HashMap<>();
        /** {@link #loose} forms of names and ids, the last resort when nothing matches exactly. */
        final Map<String, String> byLoose = new HashMap<>();

        List<Program> find(String tvgId, String name) {
            List<Program> p = tvgId == null || tvgId.isEmpty() ? null : byChannel.get(tvgId);
            if (p == null && tvgId != null && !tvgId.isEmpty()) { String id = byName.get(tvgId.toLowerCase(Locale.ROOT)); if (id != null) p = byChannel.get(id); }
            if (p == null && name != null) { String id = byName.get(name.trim().toLowerCase(Locale.ROOT)); if (id != null) p = byChannel.get(id); }
            if (p == null) for (String s : new String[]{tvgId, name}) {
                String k = loose(s);
                String id = k.isEmpty() ? null : byLoose.get(k);
                if (id != null && (p = byChannel.get(id)) != null) break;
            }
            return p == null ? Collections.emptyList() : p;
        }

        void addLoose(String key, String channel) { String k = loose(key); if (!k.isEmpty() && !byLoose.containsKey(k)) byLoose.put(k, channel); }

        int programmeCount() { int n = 0; for (List<Program> l : byChannel.values()) n += l.size(); return n; }
    }

    /**
     * A channel name or id reduced for matching between providers: "US: CNN HD", "CNN (East) FHD", "|US| CNN"
     * and "CNN.us" all become "cnn". Returns "" for null or nothing left.
     */
    static String loose(String s) {
        if (s == null) return "";
        String v = s.trim().toLowerCase(Locale.ROOT);
        v = v.replaceAll("^[|\\[(]?\\s*[a-z]{2,3}\\s*[|\\])]\\s*", "");          // |US| CNN, [UK] BBC One
        v = v.replaceAll("^[a-z]{2,3}\\s*[:|]\\s*", "");                       // US: CNN, UK | BBC One
        v = v.replaceAll("^[a-z]{2}\\s+-\\s+", "");                              // US - CNN
        v = v.replaceAll("\\.[a-z]{2,3}$", "");                                    // cnn.us
        v = v.replaceAll("[(\\[][^)\\]]*[)\\]]", " ");                            // (East), [backup]
        v = v.replaceAll("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|50fps|60fps|backup|raw)\\b", " ");
        v = v.replace("&", "and").replaceAll("[^\\p{L}\\p{N}+]", "");
        return v;
    }

    /** XMLTV time: "20261121153000 +0000" (offset optional, then local time). Returns epoch ms or -1. */
    static long parseTime(String value) {
        if (value == null) return -1;
        String v = value.trim();
        if (v.length() < 12) return -1;
        try {
            int year = Integer.parseInt(v.substring(0, 4)), month = Integer.parseInt(v.substring(4, 6)), day = Integer.parseInt(v.substring(6, 8));
            int hour = Integer.parseInt(v.substring(8, 10)), min = Integer.parseInt(v.substring(10, 12));
            int sec = v.length() >= 14 && Character.isDigit(v.charAt(12)) ? Integer.parseInt(v.substring(12, 14)) : 0;
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            c.clear();
            c.set(year, month - 1, day, hour, min, sec);
            long t = c.getTimeInMillis();
            int sp = v.indexOf(' ');
            String off = sp < 0 ? "" : v.substring(sp + 1).trim();
            if (off.length() >= 5 && (off.charAt(0) == '+' || off.charAt(0) == '-')) {
                int mins = Integer.parseInt(off.substring(1, 3)) * 60 + Integer.parseInt(off.substring(3, 5));
                t -= (off.charAt(0) == '+' ? 1 : -1) * mins * 60000L;
            } else {
                t -= TimeZone.getDefault().getOffset(t);
            }
            return t;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses the stream, keeping programmes that overlap [from, to]. */
    static Guide parse(InputStream raw, long from, long to) throws Exception {
        InputStream in = new BufferedInputStream(raw, 65536);
        in.mark(4);
        int b1 = in.read(), b2 = in.read();
        in.reset();
        if (b1 == 0x1f && b2 == 0x8b) in = new BufferedInputStream(new GZIPInputStream(in, 65536), 65536);
        XmlPullParser x = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser();
        x.setInput(in, null);
        Guide g = new Guide();
        String channel = null, progChannel = null, field = null;
        long start = 0, stop = 0;
        StringBuilder title = new StringBuilder(), desc = new StringBuilder(), name = new StringBuilder();
        boolean keep = false;
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (ev == XmlPullParser.START_TAG) {
                String tag = x.getName();
                if ("channel".equals(tag)) {
                    channel = x.getAttributeValue(null, "id");
                    if (channel != null) { g.byName.put(channel.toLowerCase(Locale.ROOT), channel); g.addLoose(channel, channel); }
                } else if ("display-name".equals(tag) && channel != null) {
                    field = "name"; name.setLength(0);
                } else if ("programme".equals(tag)) {
                    progChannel = x.getAttributeValue(null, "channel");
                    start = parseTime(x.getAttributeValue(null, "start"));
                    stop = parseTime(x.getAttributeValue(null, "stop"));
                    keep = progChannel != null && start > 0 && stop > start && stop >= from && start <= to;
                    title.setLength(0); desc.setLength(0);
                } else if (keep && "title".equals(tag) && title.length() == 0) {
                    field = "title";
                } else if (keep && "desc".equals(tag) && desc.length() == 0) {
                    field = "desc";
                }
            } else if (ev == XmlPullParser.TEXT && field != null) {
                String t = x.getText();
                if ("title".equals(field)) title.append(t);
                else if ("desc".equals(field)) { if (desc.length() < 400) desc.append(t); }
                else name.append(t);
            } else if (ev == XmlPullParser.END_TAG) {
                String tag = x.getName();
                if ("display-name".equals(tag) && "name".equals(field)) {
                    String n = name.toString().trim().toLowerCase(Locale.ROOT);
                    if (!n.isEmpty() && channel != null && !g.byName.containsKey(n)) g.byName.put(n, channel);
                    if (channel != null) g.addLoose(n, channel);
                    field = null;
                } else if ("title".equals(tag) || "desc".equals(tag)) {
                    field = null;
                } else if ("channel".equals(tag)) {
                    channel = null;
                } else if ("programme".equals(tag)) {
                    if (keep) {
                        List<Program> l = g.byChannel.get(progChannel);
                        if (l == null) { l = new ArrayList<>(); g.byChannel.put(progChannel, l); }
                        String d = desc.toString().trim();
                        if (d.length() > 300) d = d.substring(0, 297) + "…";
                        l.add(new Program(start, stop, title.toString().trim(), d));
                    }
                    keep = false;
                }
            }
        }
        for (List<Program> l : g.byChannel.values()) Collections.sort(l, (p, q) -> Long.compare(p.start, q.start));
        return g;
    }

    /** Programmes from the one airing at {@code now} onwards, at most {@code limit}. */
    static List<Program> upcoming(List<Program> all, long now, int limit) {
        List<Program> out = new ArrayList<>();
        for (Program p : all) {
            if (p.stop <= now) continue;
            out.add(p);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
