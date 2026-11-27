package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * SiliconDust HDHomeRun network tuners: finding them on the home network and turning a tuner's
 * channel lineup (lineup.json) into an M3U playlist, so the rest of WygTV (Live TV, TV guide,
 * favorites, search) treats an antenna tuner like any other M3U playlist.
 *
 * Plain Java (no Android classes), so the packet and lineup code is unit tested on the JVM.
 */
final class Hdhr {
    /** UDP port HDHomeRun devices answer discovery on. */
    static final int PORT = 65001;
    static final int TYPE_DISCOVER_REQ = 0x0002, TYPE_DISCOVER_RPY = 0x0003;
    static final int TAG_DEVICE_TYPE = 0x01, TAG_DEVICE_ID = 0x02, TAG_TUNER_COUNT = 0x10,
            TAG_LINEUP_URL = 0x27, TAG_BASE_URL = 0x2A, TAG_DEVICE_AUTH = 0x2B;
    static final long DEVICE_TYPE_TUNER = 0x00000001L, WILDCARD = 0xFFFFFFFFL;
    /** SiliconDust's cloud lookup: lists the tuners that share this network's public address. */
    static final String CLOUD_DISCOVER = "https://api.hdhomerun.com/discover";
    /** The name every HDHomeRun answers to on a home network (mDNS); tried first when adding a tuner. */
    static final String DEFAULT_HOST = "hdhomerun.local";
    /** SiliconDust's free program guide for a tuner's channels (the data behind the tuner's own guide). */
    static final String GUIDE_API = "https://api.hdhomerun.com/api/guide.php";
    /** Group for channels whose number has no major part. */
    static final String OTHER_GROUP = "Other channels";

    private Hdhr() { }

    /** One tuner, from discovery and its discover.json. */
    static final class Device {
        String id = "", name = "", model = "", base = "", lineupUrl = "", auth = "";
        int tuners;

        /** "HDHomeRun FLEX 4K  •  192.168.1.50  •  4 tuners" */
        String summary() {
            StringBuilder s = new StringBuilder(name.isEmpty() ? "HDHomeRun" : name);
            String host = hostOf(base);
            if (!host.isEmpty()) s.append("  •  ").append(host);
            if (tuners > 0) s.append("  •  ").append(tuners).append(tuners == 1 ? " tuner" : " tuners");
            if (!id.isEmpty()) s.append("  •  ID ").append(id);
            return s.toString();
        }
    }

    // ------------------------------------------------------------------ discovery packets

    /** The broadcast "who's out there?" packet: any device type, any device id. */
    static byte[] discoverRequest() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        tag(payload, TAG_DEVICE_TYPE, be32(WILDCARD));
        tag(payload, TAG_DEVICE_ID, be32(WILDCARD));
        return frame(TYPE_DISCOVER_REQ, payload.toByteArray());
    }

    /** Header (type, payload length, big endian), payload, then CRC32 of both, little endian. */
    static byte[] frame(int type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type >> 8); out.write(type);
        out.write(payload.length >> 8); out.write(payload.length);
        out.write(payload, 0, payload.length);
        CRC32 crc = new CRC32();
        byte[] body = out.toByteArray();
        crc.update(body, 0, body.length);
        long c = crc.getValue();
        out.write((int) c); out.write((int) (c >> 8)); out.write((int) (c >> 16)); out.write((int) (c >> 24));
        return out.toByteArray();
    }

    static void tag(ByteArrayOutputStream out, int tag, byte[] value) {
        out.write(tag);
        if (value.length <= 127) out.write(value.length);
        else { out.write((value.length & 0x7F) | 0x80); out.write(value.length >> 7); }
        out.write(value, 0, value.length);
    }

    static byte[] be32(long v) { return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v}; }

    /**
     * Reads a discovery reply. Returns null for anything that isn't a valid reply from a tuner
     * (bad length or CRC, another packet type, or a storage-only device such as a RECORD engine).
     * {@code fromIp} is used when old firmware sends no base URL.
     */
    static Device parseReply(byte[] data, int length, String fromIp) {
        if (data == null || length < 8 || length > data.length) return null;
        int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int len = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (type != TYPE_DISCOVER_RPY || 4 + len + 4 != length) return null;
        CRC32 crc = new CRC32();
        crc.update(data, 0, 4 + len);
        long want = crc.getValue();
        int p = 4 + len;
        long got = (data[p] & 0xFFL) | (data[p + 1] & 0xFFL) << 8 | (data[p + 2] & 0xFFL) << 16 | (data[p + 3] & 0xFFL) << 24;
        if (want != got) return null;
        Device d = new Device();
        long deviceType = -1;
        int i = 4, end = 4 + len;
        while (i + 2 <= end) {
            int t = data[i++] & 0xFF;
            int l = data[i++] & 0xFF;
            if ((l & 0x80) != 0) { if (i >= end) return null; l = (l & 0x7F) | ((data[i++] & 0xFF) << 7); }
            if (i + l > end) return null;
            if (t == TAG_DEVICE_TYPE && l == 4) deviceType = u32(data, i);
            else if (t == TAG_DEVICE_ID && l == 4) d.id = String.format(Locale.ROOT, "%08X", u32(data, i));
            else if (t == TAG_TUNER_COUNT && l == 1) d.tuners = data[i] & 0xFF;
            else if (t == TAG_BASE_URL) d.base = new String(data, i, l, StandardCharsets.UTF_8).trim();
            else if (t == TAG_LINEUP_URL) d.lineupUrl = new String(data, i, l, StandardCharsets.UTF_8).trim();
            else if (t == TAG_DEVICE_AUTH) d.auth = new String(data, i, l, StandardCharsets.UTF_8).trim();
            i += l;
        }
        if (deviceType != -1 && deviceType != DEVICE_TYPE_TUNER && d.lineupUrl.isEmpty()) return null;
        if (d.base.isEmpty() && fromIp != null && !fromIp.isEmpty()) d.base = "http://" + fromIp;
        if (d.base.isEmpty()) return null;
        d.base = d.base.replaceAll("/+$", "");
        if (d.lineupUrl.isEmpty()) d.lineupUrl = d.base + "/lineup.json";
        return d;
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xFFL) << 24 | (b[i + 1] & 0xFFL) << 16 | (b[i + 2] & 0xFFL) << 8 | (b[i + 3] & 0xFFL);
    }

    // ------------------------------------------------------------------ finding tuners (blocking: call off the UI thread)

    /** Broadcasts a discovery packet on every network and collects replies for {@code waitMs}. */
    static List<Device> discoverLocal(int waitMs) {
        Map<String, Device> found = new LinkedHashMap<>();
        byte[] request = discoverRequest();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            Set<InetAddress> targets = new LinkedHashSet<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            try {
                for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nic.isUp() || nic.isLoopback()) continue;
                    for (InterfaceAddress ia : nic.getInterfaceAddresses()) if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
                }
            } catch (Exception ignored) { }
            long deadline = System.currentTimeMillis() + waitMs;
            // Send twice, in case the first packet is dropped by Wi-Fi power saving.
            for (int round = 0; round < 2; round++) {
                for (InetAddress to : targets) {
                    try { socket.send(new DatagramPacket(request, request.length, to, PORT)); } catch (Exception ignored) { }
                }
                long roundEnd = round == 0 ? Math.min(deadline, System.currentTimeMillis() + waitMs / 2) : deadline;
                byte[] buf = new byte[2048];
                while (true) {
                    long left = roundEnd - System.currentTimeMillis();
                    if (left <= 0) break;
                    socket.setSoTimeout((int) Math.max(1, left));
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    try { socket.receive(in); } catch (SocketTimeoutException timeout) { break; }
                    Device d = parseReply(in.getData(), in.getLength(), in.getAddress().getHostAddress());
                    if (d != null) found.put(d.id.isEmpty() ? d.base : d.id, d);
                }
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(found.values());
    }

    /** Base URLs of tuners SiliconDust's cloud knows on this network (needs internet). */
    static List<String> discoverCloud() {
        List<String> out = new ArrayList<>();
        try { out.addAll(parseCloud(Library.download(CLOUD_DISCOVER, 8000))); } catch (Exception ignored) { }
        return out;
    }

    /** The cloud answer: [{"DeviceID":"…","LocalIP":"192.168.1.50","BaseURL":"http://…",…}, …]. Tuners only. */
    static List<String> parseCloud(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(json.trim());
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null || o.optString("DeviceID").isEmpty()) continue; // storage engines have StorageID instead
                String base = o.optString("BaseURL");
                if (base.isEmpty() && !o.optString("LocalIP").isEmpty()) base = "http://" + o.optString("LocalIP");
                if (!base.isEmpty()) out.add(base.replaceAll("/+$", ""));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Reads a tuner's discover.json. Throws when there is no HDHomeRun tuner at that address. */
    static Device describe(String base) throws Exception {
        base = base.replaceAll("/+$", "");
        return parseDiscoverJson(Library.download(base + "/discover.json", 8000), base);
    }

    static Device parseDiscoverJson(String json, String base) throws Exception {
        JSONObject o = new JSONObject(json.trim());
        Device d = new Device();
        d.id = o.optString("DeviceID");
        d.name = o.optString("FriendlyName");
        d.model = o.optString("ModelNumber");
        d.auth = o.optString("DeviceAuth");
        d.tuners = o.optInt("TunerCount", 0);
        d.base = o.optString("BaseURL", base).replaceAll("/+$", "");
        if (d.base.isEmpty()) d.base = base;
        d.lineupUrl = o.optString("LineupURL");
        if (d.lineupUrl.isEmpty()) {
            // RECORD engines answer discover.json too, but have no lineup.
            if (!o.optString("StorageURL").isEmpty() || d.id.isEmpty()) throw new IllegalStateException("not a tuner");
            d.lineupUrl = d.base + "/lineup.json";
        }
        return d;
    }

    /**
     * Every tuner on the network: local broadcast first, then SiliconDust's cloud lookup for routers that
     * block broadcasts. Each is confirmed with its discover.json, which also gives its name and tuner count.
     */
    static List<Device> discoverAll() {
        Map<String, Device> found = new LinkedHashMap<>();
        List<String> bases = new ArrayList<>();
        for (Device d : discoverLocal(2500)) {
            found.put(key(d), d);
            bases.add(d.base);
        }
        for (String b : discoverCloud()) if (!bases.contains(b)) bases.add(b);
        for (String b : bases) {
            try {
                Device full = describe(b);
                found.remove(b);
                for (Map.Entry<String, Device> e : new ArrayList<>(found.entrySet())) if (e.getValue().base.equals(b)) found.remove(e.getKey());
                found.put(key(full), full);
            } catch (Exception ignored) { /* keep what the broadcast said, if anything */ }
        }
        return new ArrayList<>(found.values());
    }

    /** "http://192.168.1.50:80" → "192.168.1.50". */
    static String hostOf(String url) {
        try { String h = new URI(url).getHost(); return h == null ? url : h; } catch (Exception e) { return url == null ? "" : url; }
    }

    private static String key(Device d) { return d.id.isEmpty() ? d.base : d.id; }

    /** "192.168.1.50", "192.168.1.50:80", "http://hdhr.local/" or a pasted discover.json link → "http://192.168.1.50". */
    static String baseFromInput(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)https?://.*")) s = "http://" + s;
        try {
            URI u = new URI(s);
            if (u.getHost() == null) return "";
            return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getRawAuthority();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ lineup → M3U

    /** True for a tuner's lineup link (these playlists are read from lineup.json instead of an M3U file). */
    static boolean isLineupUrl(String url) {
        if (url == null) return false;
        try {
            String path = new URI(url.trim()).getRawPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith("/lineup.json");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Turns lineup.json into M3U text. Channels are grouped by their main channel number, named after
     * the station on its first sub-channel ("Channel 4  ·  WNBC"), so 4.1, 4.2 and 4.3 sit together.
     * Copy-protected (DRM) cable channels are left out because no app can play them.
     */
    static String lineupToM3u(String json) throws Exception {
        JSONArray list = new JSONArray(json.trim());
        Map<String, String> groupNames = new LinkedHashMap<>();
        List<JSONObject> playable = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || o.optString("URL").isEmpty() || isDrm(o)) continue;
            playable.add(o);
            String major = major(o.optString("GuideNumber"));
            if (!major.isEmpty() && !groupNames.containsKey(major)) groupNames.put(major, station(o.optString("GuideName")));
        }
        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        for (JSONObject o : playable) {
            String number = o.optString("GuideNumber").trim(), name = o.optString("GuideName").trim();
            String major = major(number);
            String group = major.isEmpty() ? OTHER_GROUP : "Channel " + major + (groupNames.get(major).isEmpty() ? "" : "  ·  " + groupNames.get(major));
            String title = (number + "  " + name).trim();
            if (o.optInt("HD", 0) == 1) title += "  HD";
            m3u.append("#EXTINF:-1 tvg-id=\"").append(attr(number)).append("\" tvg-chno=\"").append(attr(number))
                    .append("\" tvg-name=\"").append(attr(name.isEmpty() ? number : name)).append("\" group-title=\"").append(attr(group))
                    .append("\",").append(title.replace('\n', ' ')).append('\n')
                    .append(o.optString("URL").trim()).append('\n');
        }
        return m3u.toString();
    }

    static boolean isDrm(JSONObject o) {
        if (o.optInt("DRM", 0) == 1) return true;
        for (String t : o.optString("Tags").split(",")) if (t.trim().equalsIgnoreCase("drm")) return true;
        return false;
    }

    /** "4.1" → "4", "702" → "702", "" → "". */
    static String major(String number) {
        String n = number == null ? "" : number.trim();
        int dot = n.indexOf('.');
        if (dot < 0) dot = n.indexOf('-');
        return dot < 0 ? n : n.substring(0, dot);
    }

    /** "WNBC-HD" → "WNBC", "KQED+" → "KQED+": the call sign without a trailing -HD/-DT/-SD. */
    static String station(String guideName) {
        String s = guideName == null ? "" : guideName.trim();
        return s.replaceAll("(?i)[-_ ](HD|DT|SD|TV)\\d*$", "").trim();
    }

    private static String attr(String v) { return v.replace('"', '\'').replace('\n', ' '); }

    /** The playlist text for any M3U-type link: a tuner's lineup is converted, anything else is downloaded as is. */
    static String playlistText(String url) throws Exception {
        if (Tablo.isChannelsUrl(url)) return Tablo.playlistText(url); // a legacy Tablo's channels
        if (isLineupUrl(url)) return lineupToM3u(Library.download(url, 30000));
        return Library.download(url, 60000);
    }

    // ------------------------------------------------------------------ program guide

    /** The tuner's address from its lineup link: "http://192.168.1.50/lineup.json" → "http://192.168.1.50". */
    static String baseOfLineup(String lineupUrl) {
        try {
            URI u = new URI(lineupUrl.trim());
            return u.getScheme() + "://" + u.getRawAuthority();
        } catch (Exception e) {
            return "";
        }
    }

    /** guide.php link for a window starting at {@code startSec} (0 = now). */
    static String guideUrl(String deviceAuth, long startSec) {
        return GUIDE_API + "?DeviceAuth=" + XtreamProtocol.encode(deviceAuth) + (startSec > 0 ? "&Start=" + startSec : "");
    }

    /**
     * Reads guide.php: [{"GuideNumber":"4.1","GuideName":"WNBC","Guide":[{"StartTime":…,"EndTime":…,"Title":…,
     * "EpisodeTitle":…,"Synopsis":…}, …]}, …] into programmes by channel number, added to {@code into}.
     * Returns the latest end time seen (seconds), so the next window can be asked for.
     */
    static long parseGuide(String json, Map<String, List<Xmltv.Program>> into) throws Exception {
        JSONArray chans = new JSONArray(json.trim());
        long latest = 0;
        for (int i = 0; i < chans.length(); i++) {
            JSONObject ch = chans.optJSONObject(i);
            if (ch == null) continue;
            String number = ch.optString("GuideNumber").trim();
            JSONArray progs = ch.optJSONArray("Guide");
            if (number.isEmpty() || progs == null) continue;
            List<Xmltv.Program> list = into.get(number);
            if (list == null) { list = new ArrayList<>(); into.put(number, list); }
            for (int j = 0; j < progs.length(); j++) {
                JSONObject p = progs.optJSONObject(j);
                if (p == null) continue;
                long start = p.optLong("StartTime"), end = p.optLong("EndTime");
                if (start <= 0 || end <= start) continue;
                boolean dup = false;
                for (Xmltv.Program have : list) if (have.start == start * 1000) { dup = true; break; }
                if (dup) continue;
                String title = p.optString("Title").trim(), episode = p.optString("EpisodeTitle").trim();
                String desc = p.optString("Synopsis").trim();
                if (!episode.isEmpty()) desc = desc.isEmpty() ? episode : episode + " — " + desc;
                list.add(new Xmltv.Program(start * 1000, end * 1000, title.isEmpty() ? "Program" : title, desc));
                latest = Math.max(latest, end);
            }
            Collections.sort(list, (x, y) -> Long.compare(x.start, y.start));
        }
        return latest;
    }

    /**
     * About {@code hours} of guide for every channel of the tuner at {@code base}. The tuner's DeviceAuth
     * (from discover.json) changes over time, so it is read fresh each time. Blocking: call off the UI thread.
     */
    static Map<String, List<Xmltv.Program>> loadGuide(String base, int hours) throws Exception {
        String auth = describe(base).auth;
        if (auth.isEmpty()) throw new IllegalStateException("tuner gave no DeviceAuth");
        Map<String, List<Xmltv.Program>> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis() / 1000, until = now + hours * 3600L, start = 0;
        for (int call = 0; call < 8; call++) {
            long latest = parseGuide(Library.download(guideUrl(auth, start), 20000), out);
            if (latest <= Math.max(start, now) || latest >= until) break;
            start = latest;
        }
        return out;
    }
}
