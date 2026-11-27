package com.wygtv.tv;

import android.content.SharedPreferences;

/**
 * When the live preview (Live TV and the full TV guide) switches to the highlighted channel: right away while
 * scrolling (a short pause), after resting on a channel for 5, 10 or 15 seconds, or only when OK is pressed.
 * The first channel after the screen opens always previews right away; this only paces later changes.
 * In "OK only" mode the first OK previews the channel and a second OK opens it full screen.
 */
final class PreviewDelay {
    static final String PREF = "preview_delay";
    static final String OK_ONLY = "ok";
    static final String DEFAULT = "quick";
    static final String[] KEYS = {"quick", "5", "10", "15", OK_ONLY};
    static final String[] LABELS = {"Quick (as you scroll)", "After 5 seconds on a channel", "After 10 seconds on a channel", "After 15 seconds on a channel", "Only when I press OK"};
    private static final int QUICK_MS = 700;

    private PreviewDelay() { }

    static String key(SharedPreferences p) {
        String k = p.getString(PREF, DEFAULT);
        return Menus.indexOf(KEYS, k) == 0 && !KEYS[0].equals(k) ? DEFAULT : k;
    }

    static String label(SharedPreferences p) { return LABELS[Menus.indexOf(KEYS, key(p))]; }

    static boolean okOnly(SharedPreferences p) { return OK_ONLY.equals(key(p)); }

    /** Milliseconds to rest on a channel before it previews, or -1 for OK only. */
    static int ms(SharedPreferences p) {
        String k = key(p);
        if (OK_ONLY.equals(k)) return -1;
        if (DEFAULT.equals(k)) return QUICK_MS;
        try { return Integer.parseInt(k) * 1000; } catch (NumberFormatException e) { return QUICK_MS; }
    }
}
