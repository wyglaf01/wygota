package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tuner's channels from its lineup.json (the list at hdhomerun.local/lineup.html), grouped by main
 * channel number exactly like the Android app: 4.1, 4.2 and 4.3 sit together under "Channel 4 · WNBC".
 * Copy-protected (DRM) channels are left out because no player can show them.
 */
final class OtaLineup {
    static final class Channel {
        final String number, name, url, group;
        final boolean hd;

        Channel(String number, String name, String url, String group, boolean hd) {
            this.number = number; this.name = name; this.url = url; this.group = group; this.hd = hd;
        }

        /** "4.1  WNBC-HD" */
        String title() { return (number + "  " + name).trim(); }

        /** "  HD" for HD channels whose name doesn't already say so. */
        String hdTag() { return hd && !name.toUpperCase(java.util.Locale.ROOT).contains("HD") ? "  HD" : ""; }
    }

    private OtaLineup() { }

    static List<Channel> parse(String json) throws Exception {
        JSONArray list = new JSONArray(json.trim());
        Map<String, String> groupNames = new LinkedHashMap<>();
        List<JSONObject> playable = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || o.optString("URL").isEmpty() || Hdhr.isDrm(o)) continue;
            playable.add(o);
            String major = Hdhr.major(o.optString("GuideNumber"));
            if (!major.isEmpty() && !groupNames.containsKey(major)) groupNames.put(major, Hdhr.station(o.optString("GuideName")));
        }
        List<Channel> out = new ArrayList<>();
        for (JSONObject o : playable) {
            String number = o.optString("GuideNumber").trim(), name = o.optString("GuideName").trim();
            String major = Hdhr.major(number);
            String group = major.isEmpty() ? Hdhr.OTHER_GROUP
                    : "Channel " + major + (groupNames.get(major).isEmpty() ? "" : "  ·  " + groupNames.get(major));
            out.add(new Channel(number, name, o.optString("URL").trim(), group, o.optInt("HD", 0) == 1));
        }
        return out;
    }

    /** Blocking: call off the UI thread. */
    static List<Channel> load(String lineupUrl) throws Exception {
        if (OtaTablo.isTablo(lineupUrl)) return OtaTablo.load(lineupUrl); // a black Tablo
        return parse(Library.download(lineupUrl, 30000));
    }
}
