package com.wygtv.tv;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supplemental TV guides: extra XMLTV links (saved once, for every playlist) that fill in channels the
 * playlist's own guide leaves empty. Each link is downloaded at most every 12 hours, cached on disk, and
 * only programmes near "now" are kept in memory.
 */
final class ExtraGuides {
    /** JSON array of XMLTV links, in the order they are tried. Saved in prefs, so Backup includes it. */
    static final String PREF = "extra_epg_urls";
    /** true: the supplemental guides are used first and the provider's guide only fills what they lack. */
    static final String PREF_FIRST = "extra_epg_first";

    /** Guides in memory are re-read from disk this often, so programmes that ended drop out. */
    private static final long MAX_AGE = 12 * 3600_000L;

    private static final class Slot {
        Xmltv.Guide guide;
        long loadedAt;
        String error = "";
    }

    private final File cacheDir;
    private final SharedPreferences prefs;
    private final Map<String, Slot> slots = new HashMap<>();

    ExtraGuides(File cacheDir, SharedPreferences prefs) { this.cacheDir = cacheDir; this.prefs = prefs; }

    // ------------------------------------------------------------------ saved links

    static List<String> urls(SharedPreferences prefs) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray l = new JSONArray(prefs.getString(PREF, "[]"));
            for (int i = 0; i < l.length(); i++) { String u = l.optString(i).trim(); if (!u.isEmpty() && !out.contains(u)) out.add(u); }
        } catch (JSONException ignored) { }
        return out;
    }

    List<String> urls() { return urls(prefs); }

    static void save(SharedPreferences prefs, List<String> urls) {
        JSONArray l = new JSONArray();
        for (String u : urls) l.put(u);
        prefs.edit().putString(PREF, l.toString()).apply();
    }

    boolean first() { return prefs.getBoolean(PREF_FIRST, false); }

    // ------------------------------------------------------------------ lookups

    /**
     * Programmes for one channel from the first of {@code sources} that knows it (matched by tvg-id, then by
     * name), in the provider's get_short_epg format; empty when none does. Blocking: call off the UI thread.
     */
    JSONArray lookup(List<String> sources, String tvgId, String name, int limit) throws InterruptedException {
        for (String url : sources) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Xmltv.Guide g = guide(url);
            if (g == null) continue;
            List<Xmltv.Program> progs = Xmltv.upcoming(g.find(tvgId, name), System.currentTimeMillis(), Math.max(1, limit));
            if (!progs.isEmpty()) return Library.listings(progs);
        }
        return new JSONArray();
    }

    /** One guide, loaded from the disk cache or the network. Only one download per link at a time. */
    Xmltv.Guide guide(String url) {
        Slot s;
        synchronized (slots) { s = slots.get(url); if (s == null) { s = new Slot(); slots.put(url, s); } }
        synchronized (s) {
            long now = System.currentTimeMillis();
            if (s.guide != null && now - s.loadedAt < MAX_AGE / 2) return s.guide;
            if (s.guide == null && !s.error.isEmpty() && now - s.loadedAt < 10 * 60_000L) return null;
            s.loadedAt = now;
            try {
                File f = cacheFile(url);
                if (!f.exists() || now - f.lastModified() > AutoRefresh.maxAge(prefs)) Library.fetchTo(url, f);
                try (InputStream in = new FileInputStream(f)) { s.guide = Xmltv.parse(in, now - 3 * 3600_000L, now + 30 * 3600_000L); }
                s.error = "";
            } catch (Throwable e) {
                s.guide = null;
                s.error = "could not load (" + e.getClass().getSimpleName() + ")";
            }
            return s.guide;
        }
    }

    /**
     * Downloads one guide again now (Update now, or a scheduled update). The old copy stays in use if the
     * download fails. Returns "" when fine, else the problem. Blocking: call off the UI thread.
     */
    String reload(String url) {
        Slot s;
        synchronized (slots) { s = slots.get(url); if (s == null) { s = new Slot(); slots.put(url, s); } }
        synchronized (s) {
            long now = System.currentTimeMillis();
            try {
                File f = cacheFile(url);
                Library.fetchTo(url, f);
                try (InputStream in = new FileInputStream(f)) { s.guide = Xmltv.parse(in, now - 3 * 3600_000L, now + 30 * 3600_000L); }
                s.loadedAt = now;
                s.error = "";
                return "";
            } catch (Throwable e) {
                String err = "could not load (" + e.getClass().getSimpleName() + ")";
                if (s.guide == null) { s.error = err; s.loadedAt = now; }
                return err;
            }
        }
    }

    /** "loaded, 412 channels", "could not load (…)" or "" when not loaded yet. Never waits on a download. */
    String status(String url) {
        Slot s;
        synchronized (slots) { s = slots.get(url); }
        if (s == null) return "";
        Xmltv.Guide g = s.guide;
        return g != null ? "loaded, " + g.byChannel.size() + " channels" : s.error;
    }

    /** Forgets one guide (memory and disk) so it downloads again next time. */
    void drop(String url) {
        synchronized (slots) { slots.remove(url); }
        cacheFile(url).delete();
    }

    /** Forgets every guide in memory and removes cached files for links no longer saved (after edits or a restore). */
    void clear() {
        synchronized (slots) { slots.clear(); }
        List<String> keep = new ArrayList<>();
        for (String u : urls()) keep.add(cacheFile(u).getName());
        File[] files = cacheDir.listFiles();
        if (files != null) for (File f : files) if (f.getName().startsWith("extra_guide_") && !keep.contains(f.getName())) f.delete();
    }

    private File cacheFile(String url) { return new File(cacheDir, "extra_guide_" + M3u.idFor(url) + ".xml"); }

    static JSONObject wrap(JSONArray listings) {
        try { return new JSONObject().put("epg_listings", listings); } catch (JSONException e) { return new JSONObject(); }
    }
}
