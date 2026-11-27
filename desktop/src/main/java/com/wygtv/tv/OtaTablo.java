package com.wygtv.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A black Tablo in WygOTA for Linux, using the shared Tablo.java: it is handled as a tuner whose "lineup" link is
 * the Tablo's http://IP:8885/guide/channels. Its channels' links are stand-ins that are tuned as they play
 * ({@link #playable}), and its TV guide comes from the Tablo itself.
 */
final class OtaTablo {
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z-]+)=\"([^\"]*)\"");

    private OtaTablo() { }

    /** The Tablo as a tuner (lineupUrl is its channel list). */
    static Hdhr.Device asDevice(Tablo.Device t) {
        Hdhr.Device d = new Hdhr.Device();
        d.id = t.id;
        d.name = t.model.isEmpty() ? (t.name.isEmpty() ? "Tablo" : t.name) : t.model;
        d.model = t.model;
        d.base = t.base;
        d.lineupUrl = Tablo.channelsUrl(t.base);
        d.tuners = t.tuners;
        return d;
    }

    static boolean isTablo(String lineupUrl) { return Tablo.isChannelsUrl(lineupUrl); }

    /** Blocking. Throws Tablo.LockedException when the Tablo refuses other apps. */
    static Hdhr.Device describe(String address) throws Exception { return asDevice(Tablo.describe(Tablo.baseFromInput(address))); }

    /** Tablos on this network (Tablo's cloud lookup), as tuners. Blocking. */
    static List<Hdhr.Device> discoverAll() {
        List<Hdhr.Device> out = new ArrayList<>();
        for (Tablo.Device t : Tablo.discoverAll()) out.add(asDevice(t));
        return out;
    }

    /** The Tablo's channels, grouped like an HDHomeRun's. Blocking. */
    static List<OtaLineup.Channel> load(String channelsUrl) throws Exception {
        return parseM3u(Tablo.playlistText(channelsUrl));
    }

    /** The M3U text Tablo.playlistText makes → channels. */
    static List<OtaLineup.Channel> parseM3u(String m3u) {
        List<OtaLineup.Channel> out = new ArrayList<>();
        String[] lines = m3u.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXTINF")) continue;
            String url = lines[i + 1].trim();
            String number = "", group = "", name = "";
            Matcher m = ATTR.matcher(l);
            while (m.find()) {
                if (m.group(1).equals("tvg-chno")) number = m.group(2);
                else if (m.group(1).equals("group-title")) group = m.group(2);
                else if (m.group(1).equals("tvg-name")) name = m.group(2);
            }
            String title = l.substring(l.lastIndexOf(',') + 1).trim();
            boolean hd = title.contains("  HD");
            if (!url.isEmpty() && !number.isEmpty()) out.add(new OtaLineup.Channel(number, name.isEmpty() ? number : name, url, group, hd));
        }
        return out;
    }

    /** A channel's link to hand to VLC: a Tablo channel is tuned first (blocking), any other link is used as it is. */
    static String playable(String url) throws Exception {
        return Tablo.isWatchUrl(url) ? Tablo.resolveWatch(url) : url;
    }
}
