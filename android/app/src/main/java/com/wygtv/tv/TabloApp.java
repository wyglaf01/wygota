package com.wygtv.tv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import java.util.Locale;

/**
 * The white 4th Gen Tablo (2023 and later), and any Tablo whose firmware refuses other apps.
 *
 * A 4th Gen Tablo only talks to Tablo's own apps (it checks every request against your Tablo account), so neither
 * WygTV nor WygOTA can list or play its channels. Like AirTV, once switched on in Settings, "📡 Tablo" sits in the
 * side menu, on Home and in the Live TV menu, and pressing it opens the Tablo app. Back returns here.
 * Older black Tablos play inside the app instead: see {@link Tablo}.
 */
final class TabloApp {
    static final String PREF_ON = "tablo_app_on", PREF_NAME = "tablo_app_name";
    static final String DEFAULT_NAME = "Tablo";

    private TabloApp() { }

    static boolean isOn(SharedPreferences p) { return p.getBoolean(PREF_ON, false); }

    static String nameOf(SharedPreferences p) {
        String raw = p.getString(PREF_NAME, "").trim();
        return raw.isEmpty() ? DEFAULT_NAME : AirTv.cleanName(raw);
    }

    /**
     * The installed Tablo app. Its package name differs between Android TV, Fire TV and phones, so any installed
     * app whose package mentions "tablo" counts; one that isn't the legacy app wins.
     */
    static String installed(MainActivity a) {
        PackageManager pm = a.getPackageManager();
        String pick = null;
        try {
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                String pkg = info.packageName;
                if (pkg == null || !pkg.toLowerCase(Locale.ROOT).contains("tablo") || pkg.equals(a.getPackageName())) continue;
                if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;
                String label = String.valueOf(pm.getApplicationLabel(info)).toLowerCase(Locale.ROOT);
                boolean legacy = label.contains("legacy") || pkg.toLowerCase(Locale.ROOT).contains("legacy");
                if (pick == null || !legacy) pick = pkg;
                if (!legacy) break;
            }
        } catch (Exception ignored) { }
        return pick;
    }

    /** Opens the Tablo app, or its store page when it isn't installed. */
    static void open(MainActivity a) {
        String pkg = installed(a);
        if (pkg != null) {
            PackageManager pm = a.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { a.startActivity(i); return; } catch (Exception ignored) { }
            }
        }
        new Dialogs.Builder(a).setTitle("The Tablo app isn't installed")
                .setMessage("4th Gen Tablo channels play in the free Tablo app. Install \"Tablo\" from your TV's app store and sign in with your Tablo account. Then this button opens it.")
                .setPositiveButton("Find the Tablo app", (d, w) -> store(a))
                .setNegativeButton("Close", null).show();
    }

    private static void store(MainActivity a) {
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Tablo&c=apps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(market); return; } catch (Exception ignored) { }
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?s=Tablo")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { }
        Toast.makeText(a, "Search for Tablo in your TV's app store.", Toast.LENGTH_LONG).show();
    }

    /** Line in the Tablo settings list. */
    static String summary(MainActivity a) {
        return isOn(a.prefs) ? "On  •  📡 " + nameOf(a.prefs) + " opens the Tablo app" : "Off  •  for the white 4th Gen Tablo: adds a button that opens the Tablo app";
    }

    /** Turn the Tablo app button on or off, rename it, or open the app now. */
    static void settings(MainActivity a, Runnable redraw) {
        boolean on = isOn(a.prefs);
        String[] items = on ? new String[]{"Turn off (remove 📡 " + nameOf(a.prefs) + " from the menus)", "Rename", "Open the Tablo app now", "Close"}
                : new String[]{"Turn on: show 📡 Tablo in the menus", "Open the Tablo app now", "Close"};
        new Dialogs.Builder(a).setTitle("Tablo 4th Gen  •  plays in the Tablo app").setItems(items, (d, w) -> {
            String pick = items[w];
            if (pick.startsWith("Turn on")) {
                a.prefs.edit().putBoolean(PREF_ON, true).apply();
                redraw.run();
                explain(a);
            } else if (pick.startsWith("Turn off")) {
                a.prefs.edit().putBoolean(PREF_ON, false).apply();
                redraw.run();
            } else if (pick.equals("Rename")) rename(a, redraw);
            else if (pick.startsWith("Open")) open(a);
        }).show();
    }

    static void explain(MainActivity a) {
        if (a.library.isEmpty()) {
            // No tuner or playlist yet, so there are no menus to put the button in: offer the Tablo app straight away.
            new Dialogs.Builder(a).setTitle("📡 " + nameOf(a.prefs) + " is on")
                    .setMessage("The white 4th Gen Tablo only shares its channels with Tablo's own app, so it plays there.\n\n"
                            + "The 📡 " + nameOf(a.prefs) + " button appears in " + Brand.NAME + "'s menus once you also add "
                            + (Brand.OTA ? "an HDHomeRun or a black Tablo." : "a playlist, an HDHomeRun or a black Tablo."))
                    .setPositiveButton("Open the Tablo app", (d, w) -> open(a))
                    .setNegativeButton("Close", null).show();
            return;
        }
        new Dialogs.Builder(a).setTitle("📡 " + nameOf(a.prefs) + " added")
                .setMessage("It's now in the side menu, on Home and in Live TV, in the same places as an antenna tuner.\n\n"
                        + "The white 4th Gen Tablo only shares its channels with Tablo's own app, so pressing 📡 " + nameOf(a.prefs)
                        + " opens the Tablo app, and Back returns to " + Brand.NAME + "."
                        + (installed(a) != null ? "" : "\n\nThe Tablo app isn't installed on this device yet: the first press offers to find it."))
                .setPositiveButton("OK", null).show();
    }

    private static void rename(MainActivity a, Runnable redraw) {
        android.widget.EditText field = new android.widget.EditText(a);
        field.setSingleLine(true);
        field.setText(nameOf(a.prefs));
        field.setSelectAllOnFocus(true);
        new Dialogs.Builder(a).setTitle("Name for your Tablo button").setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    String n = field.getText().toString().trim();
                    a.prefs.edit().putString(PREF_NAME, n.isEmpty() ? DEFAULT_NAME : AirTv.cleanName(n)).apply();
                    redraw.run();
                })
                .setNegativeButton("Cancel", null).show();
    }
}
