package com.wygtv.tv;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Settings → Appearance → Channel order: how channels line up in the TV guide, the Live TV guide and
 * when changing channel with CH+/CH-. Playlist order (as the provider sends them) is the default.
 */
final class ChannelOrder {
    private ChannelOrder() { }

    static final String PREF = "channel_order";
    static final String[] KEYS = {"playlist", "number", "name", "name_desc"};
    static final String[] LABELS = {"Playlist order (as the provider lists them)", "Channel number (1, 2, 3…)", "Name A to Z", "Name Z to A"};
    static final String DEFAULT = "playlist";

    static String key(SharedPreferences prefs) { return prefs.getString(PREF, DEFAULT); }

    static int index(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 0;
    }

    static String label(SharedPreferences prefs) { return LABELS[index(key(prefs))]; }

    static JSONArray apply(SharedPreferences prefs, JSONArray channels) { return sort(channels, key(prefs)); }

    /** A sorted copy (the same array for playlist order). Ties keep their playlist order. */
    static JSONArray sort(JSONArray channels, String key) {
        if (channels == null || channels.length() < 2 || key == null || key.equals(DEFAULT) || index(key) == 0) return channels;
        List<JSONObject> list = new ArrayList<>();
        List<Object> other = new ArrayList<>();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject o = channels.optJSONObject(i);
            if (o != null) list.add(o); else other.add(channels.opt(i));
        }
        switch (key) {
            case "number":
                Collections.sort(list, (x, y) -> Double.compare(number(x), number(y)));
                break;
            case "name":
                Collections.sort(list, (x, y) -> x.optString("name").trim().compareToIgnoreCase(y.optString("name").trim()));
                break;
            case "name_desc":
                Collections.sort(list, (x, y) -> y.optString("name").trim().compareToIgnoreCase(x.optString("name").trim()));
                break;
            default:
                return channels;
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        for (Object o : other) out.put(o);
        return out;
    }

    /** "5", "5.1" or "5-1" → 5.1; channels without a number go last. */
    static double number(JSONObject o) {
        String n = o.optString("num", "").trim().replace('-', '.');
        if (n.isEmpty()) return Double.MAX_VALUE;
        try { return Double.parseDouble(n); } catch (NumberFormatException e) { return Double.MAX_VALUE; }
    }
}
