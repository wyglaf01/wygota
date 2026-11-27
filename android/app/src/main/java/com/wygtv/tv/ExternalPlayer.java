package com.wygtv.tv;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings → Manage player → Player: play in WygTV's own player (the default) or hand the stream to
 * another video app on the TV (VLC, MX Player, Just Player…), for everything or only Live TV or only
 * Movies and Series.
 */
final class ExternalPlayer {
    private ExternalPlayer() { }

    /** "" = built-in player, "ask" = Android asks which app each time, otherwise the app's package name. */
    static final String PREF = "external_player";
    /** Which videos go to the external app. */
    static final String PREF_FOR = "external_player_for";
    static final String[] FOR_KEYS = {"all", "live", "vod"};
    static final String[] FOR_LABELS = {"Everything", "Live TV only", "Movies and Series only"};
    static final String ASK = "ask";

    static String choice(SharedPreferences prefs) { return prefs.getString(PREF, ""); }

    static String forKey(SharedPreferences prefs) { return prefs.getString(PREF_FOR, "all"); }

    static int forIndex(String key) {
        for (int i = 0; i < FOR_KEYS.length; i++) if (FOR_KEYS[i].equals(key)) return i;
        return 0;
    }

    /** True when this kind of video ("live", "vod" or "series") should open in the external app. */
    static boolean usedFor(String choice, String forKey, String type) {
        if (choice == null || choice.isEmpty()) return false;
        if ("live".equals(forKey)) return "live".equals(type);
        if ("vod".equals(forKey)) return !"live".equals(type);
        return true;
    }

    private static Intent viewIntent(String url) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*");
    }

    /** Video apps on this device that can play a stream link (WygTV left out). */
    static List<ResolveInfo> apps(MainActivity a) {
        List<ResolveInfo> out = new ArrayList<>();
        PackageManager pm = a.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(viewIntent("http://example.com/live/1.ts"), 0);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo == null ? null : r.activityInfo.packageName;
            if (pkg == null || pkg.equals(a.getPackageName()) || !seen.add(pkg)) continue;
            out.add(r);
        }
        return out;
    }

    static String appName(MainActivity a, String pkg) {
        try {
            PackageManager pm = a.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** What the Settings row shows. */
    static String label(MainActivity a) {
        String c = choice(a.prefs);
        if (c.isEmpty()) return "WygTV built-in player (default)";
        String who = ASK.equals(c) ? "an external app (Android asks which)" : appName(a, c) + " (external)";
        return who + "  •  for " + FOR_LABELS[forIndex(forKey(a.prefs))].toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Opens the stream in the chosen external app when Settings say so. Returns false to play it in
     * WygTV's own player instead (built-in chosen, or the external app is gone).
     */
    static boolean open(MainActivity a, String type, String url, String title) {
        if (Tablo.isWatchUrl(url)) return false; // a Tablo channel gets its stream link as it plays, so only the built-in player can play it
        String c = choice(a.prefs);
        if (!usedFor(c, forKey(a.prefs), type)) return false;
        Intent i = viewIntent(url).putExtra("title", title).putExtra("itemTitle", title)
                .putExtra("forcename", title).putExtra("secure_uri", true);
        try {
            if (ASK.equals(c)) {
                a.startActivity(Intent.createChooser(i, "Play “" + title + "” with"));
            } else {
                i.setPackage(c);
                a.startActivity(i);
            }
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(a, "The external player isn't installed any more. Playing in WygTV. Settings > Manage player > Player changes it.", Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
