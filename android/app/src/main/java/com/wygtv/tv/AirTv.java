package com.wygtv.tv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

/**
 * AirTV antenna boxes (AirTV 2, AirTV Anywhere, AirTV Classic).
 *
 * Unlike an HDHomeRun, an AirTV has no open connection for other apps: it has no channel list or stream links on the
 * home network, and its channels only play inside the Sling TV app (free with an AirTV, no subscription needed).
 * So WygTV can't play AirTV channels itself. Instead, once switched on in Settings, "📡 AirTV" sits in the side menu,
 * on Home and in the Live TV menu, in the same places as an HDHomeRun tuner, and pressing it opens Sling's local channels.
 */
final class AirTv {
    static final String PREF_ON = "airtv_on", PREF_NAME = "airtv_name";
    static final String DEFAULT_NAME = "AirTV";
    /** Sling TV on Android TV, Google TV, phones and Fire TV. */
    static final String SLING = "com.sling";

    private AirTv() { }

    static boolean isOn(SharedPreferences p) { return p.getBoolean(PREF_ON, false); }

    /** The name it shows under, first letter capitalized; "AirTV" when left empty. */
    static String nameOf(SharedPreferences p) { return cleanName(p.getString(PREF_NAME, "")); }

    static String cleanName(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return DEFAULT_NAME;
        return t.substring(0, 1).toUpperCase(java.util.Locale.getDefault()) + t.substring(1);
    }

    static boolean slingInstalled(MainActivity a) {
        try { a.getPackageManager().getPackageInfo(SLING, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    /** Opens the Sling app (where the AirTV's channels play), or its store page when it isn't installed. */
    static void open(MainActivity a) {
        PackageManager pm = a.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(SLING);
        if (i == null) i = pm.getLaunchIntentForPackage(SLING);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { a.startActivity(i); return; } catch (Exception ignored) { }
        }
        new Dialogs.Builder(a).setTitle("Sling TV isn't installed")
                .setMessage("AirTV channels play in the free Sling TV app (no subscription needed for your antenna channels). Install Sling TV, sign in or make a free account, and add your AirTV there. Then this button opens it.")
                .setPositiveButton("Get Sling TV", (d, w) -> store(a))
                .setNegativeButton("Close", null).show();
    }

    private static void store(MainActivity a) {
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SLING)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(market); return; } catch (Exception ignored) { }
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=" + SLING)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { }
        Toast.makeText(a, "Search for Sling TV in your TV's app store.", Toast.LENGTH_LONG).show();
    }

    /** Settings line under Manage playlists. */
    static String summary(MainActivity a) {
        return isOn(a.prefs) ? "On  •  📡 " + nameOf(a.prefs) + " opens the Sling app  •  press OK to change" : "Off  •  press OK to add your AirTV to the menus";
    }

    /** Settings > Manage playlists > AirTV antenna TV. */
    static void settings(MainActivity a, Runnable redraw) {
        boolean on = isOn(a.prefs);
        String[] items = on ? new String[]{"Turn off (remove 📡 " + nameOf(a.prefs) + " from the menus)", "Rename", "Open Sling now", "Close"}
                : new String[]{"Turn on: show 📡 AirTV in the menus", "Open Sling now", "Close"};
        // A pick list can't carry a message too, so the explanation shows when you turn it on (see explain).
        new Dialogs.Builder(a).setTitle("AirTV antenna TV  •  plays in Sling TV")
                .setItems(items, (d, w) -> {
                    String pick = items[w];
                    if (pick.startsWith("Turn on")) {
                        a.prefs.edit().putBoolean(PREF_ON, true).apply();
                        redraw.run();
                        explain(a);
                    } else if (pick.startsWith("Turn off")) {
                        a.prefs.edit().putBoolean(PREF_ON, false).apply();
                        redraw.run();
                    } else if (pick.equals("Rename")) rename(a, redraw);
                    else if (pick.equals("Open Sling now")) open(a);
                }).show();
    }

    /** Why AirTV opens Sling instead of playing in WygTV. */
    static void explain(MainActivity a) {
        new Dialogs.Builder(a).setTitle("📡 " + nameOf(a.prefs) + " added")
                .setMessage("It's now in the side menu, on Home and in Live TV, in the same places as an HDHomeRun tuner.\n\n"
                        + "AirTV boxes don't share their channels with other apps the way HDHomeRun does: they only play in the free Sling TV app "
                        + "(no subscription needed for antenna channels). So pressing 📡 " + nameOf(a.prefs) + " opens Sling, and Back returns to WygTV."
                        + (slingInstalled(a) ? "" : "\n\nSling TV isn't installed on this TV yet: the first press offers to install it."))
                .setPositiveButton("OK", null).show();
    }

    private static void rename(MainActivity a, Runnable redraw) {
        android.widget.EditText field = new android.widget.EditText(a);
        field.setSingleLine(true);
        field.setText(nameOf(a.prefs));
        field.setSelectAllOnFocus(true);
        new Dialogs.Builder(a).setTitle("Name for your AirTV").setView(field)
                .setPositiveButton("Save", (d, w) -> { a.prefs.edit().putString(PREF_NAME, cleanName(field.getText().toString())).apply(); redraw.run(); })
                .setNegativeButton("Cancel", null).show();
    }
}
