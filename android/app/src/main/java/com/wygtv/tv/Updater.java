package com.wygtv.tv;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates. Each promoted release (Release WygTV workflow) publishes wygtv.apk plus version.json to the
 * "latest" GitHub release. WygTV compares that versionCode with its own and, when newer,
 * downloads the APK and hands it to the system installer, which installs it over the old one
 * (playlists, favourites and settings are kept).
 */
final class Updater {
    /** The beta is its own app (com.wygtv.tv.beta) and updates from the beta; the release updates from the latest release. */
    static final boolean BETA = BuildConfig.APPLICATION_ID.endsWith(".beta");
    static final String RELEASE = Brand.OTA ? Brand.OTA_RELEASE : BETA ? "https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygtv-beta/"
            : "https://github.com/wyglaf01/WygTV-AndroidTV/releases/latest/download/";
    static final String APK = Brand.OTA ? Brand.OTA_APK : BETA ? "wygtv-beta.apk" : "wygtv.apk";
    static final String PREF_AUTO = "update_check_startup";
    /** versionCode WygTV had when it started installing an update; read again on the next start. */
    static final String PREF_FROM = "update_from";

    private Updater() { }

    static boolean isNewer(int latest, int installed) { return latest > installed; }

    static int installedCode(MainActivity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    static String installedName(MainActivity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    /** Called once at start-up, before the update check: says "Updated to …" after an in-app update and tidies up. */
    static void afterUpdate(MainActivity a) {
        if (Brand.STORE) return;
        int from = a.prefs.getInt(PREF_FROM, -1);
        if (from < 0) return;
        int now = installedCode(a);
        if (now <= from) return; // the install was cancelled or failed; the download is kept for next time
        a.prefs.edit().remove(PREF_FROM).remove("update_downloaded").apply();
        try { ApkProvider.file(a).delete(); } catch (Exception ignored) { }
        Toast.makeText(a, Brand.NAME + " updated to " + installedName(a) + ". Welcome back!", Toast.LENGTH_LONG).show();
    }

    /** Called once at start-up: quietly offers an update when there is one. */
    static void checkAtStartup(MainActivity a) {
        if (Brand.STORE) return;
        if (a.prefs.getBoolean(PREF_AUTO, true)) check(a, false);
    }

    /** Looks for a newer WygTV. When {@code manual}, also says so when it is already up to date or the check fails. */
    static void check(MainActivity a, boolean manual) {
        if (Brand.STORE) return;
        if (manual) Toast.makeText(a, "Checking for a " + Brand.NAME + " update…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JSONObject info = null;
            try { info = new JSONObject(new String(fetch(RELEASE + "version.json"), "UTF-8")); } catch (Exception ignored) { }
            final JSONObject latest = info;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (latest == null) { if (manual) Menus.alert(a, "Couldn't reach the " + Brand.NAME + " update page. Check the internet connection and try again."); return; }
                int code = latest.optInt("versionCode", -1), mine = installedCode(a);
                String name = latest.optString("versionName", String.valueOf(code));
                if (!isNewer(code, mine)) { if (manual) Toast.makeText(a, Brand.NAME + " " + installedName(a) + " is the latest version.", Toast.LENGTH_LONG).show(); return; }
                new Dialogs.Builder(a).setTitle(Brand.NAME + " update available")
                        .setMessage("Version " + name + " is ready (you have " + installedName(a) + ").\n\nIt installs over this one, and your playlists, favourites and settings are kept.")
                        .setPositiveButton("Update now", (d, w) -> download(a, name, code))
                        .setNegativeButton("Later", null).show();
            });
        }).start();
    }

    private static void download(MainActivity a, String name, int code) {
        // Already downloaded (e.g. the person went to allow installs first): go straight to installing.
        if (ApkProvider.file(a).exists() && a.prefs.getInt("update_downloaded", -1) == code) { install(a); return; }
        a.prefs.edit().remove("update_downloaded").apply();
        AlertDialog progress = new Dialogs.Builder(a).setTitle("Downloading " + Brand.NAME + " " + name).setMessage("Starting…").setCancelable(false).show();
        new Thread(() -> {
            File apk = ApkProvider.file(a);
            String error = null;
            try {
                apk.getParentFile().mkdirs();
                HttpURLConnection c = open(RELEASE + APK);
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0, shown = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done - shown > 256 * 1024) {
                            shown = done;
                            final String msg = total > 0 ? (done * 100 / total) + "%  (" + done / 1048576 + " of " + total / 1048576 + " MB)" : done / 1048576 + " MB";
                            a.runOnUiThread(() -> progress.setMessage(msg));
                        }
                    }
                } finally { c.disconnect(); }
                if (total > 0 && apk.length() != total) error = "The download stopped early. Try again.";
            } catch (Exception e) { error = "The download failed: " + e.getMessage(); }
            final String failed = error;
            a.runOnUiThread(() -> {
                try { progress.dismiss(); } catch (Exception ignored) { }
                if (a.isFinishing()) return;
                if (failed != null) { Menus.alert(a, failed); return; }
                a.prefs.edit().putInt("update_downloaded", code).apply();
                install(a);
            });
        }).start();
    }

    /** True while WygTV waits for the person to allow "install unknown apps"; the install carries on when they come back. */
    private static boolean waitingForPermission;

    static boolean canInstall(MainActivity a) {
        return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls();
    }

    /** Installs the downloaded APK, first sending the person to "install unknown apps" when WygTV isn't allowed yet. */
    static void install(MainActivity a) {
        if (!canInstall(a)) { askPermission(a); return; }
        waitingForPermission = false;
        // Android always closes an app while it installs a new version of it, so say so first
        // instead of WygTV just vanishing from the screen.
        new Dialogs.Builder(a).setTitle(Brand.NAME + " will close to update")
                .setMessage("Android closes " + Brand.NAME + " while the new version installs. That's normal, nothing has crashed.\n\n"
                        + "After you choose Install on the next screen, " + Brand.NAME + " reopens by itself when it's done. "
                        + "If it doesn't, open " + Brand.NAME + " again from the Home screen. Your playlists, favourites and settings are kept.")
                .setPositiveButton("Install now", (d, w) -> startInstall(a))
                .setNegativeButton("Later", null).show();
    }

    private static void startInstall(MainActivity a) {
        a.prefs.edit().putInt(PREF_FROM, installedCode(a)).commit();
        Toast.makeText(a, "Installing the " + Brand.NAME + " update…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            try { installWithSession(a); } catch (Exception e) { error = e.getMessage(); }
            if (error == null) return;
            // Some TVs refuse the session installer; fall back to the classic install screen.
            a.runOnUiThread(() -> installWithIntent(a));
        }).start();
    }

    /** Called from MainActivity.onStart: carries on with the install after the permission screen. */
    static void onReturn(MainActivity a) {
        if (Brand.STORE) return;
        if (!waitingForPermission || !ApkProvider.file(a).exists()) return;
        if (canInstall(a)) { install(a); return; }
        waitingForPermission = false;
        new Dialogs.Builder(a).setTitle(Brand.NAME + " still can't install updates")
                .setMessage("The update is downloaded. Allow " + Brand.NAME + " to install apps, then press Back and it installs straight away.")
                .setPositiveButton("Open setting", (d, w) -> askPermission(a))
                .setNegativeButton("Cancel", null).show();
    }

    private static void askPermission(MainActivity a) {
        waitingForPermission = true;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            Toast.makeText(a, "Turn on \"Allow from this source\" for " + Brand.NAME + ", then press Back. The update installs right away.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            waitingForPermission = false;
            Menus.alert(a, "Allow " + Brand.NAME + " to install apps: Settings > Apps > Special app access > Install unknown apps (on Fire TV: Settings > My Fire TV > Developer options > Install unknown apps). Then choose Update again; the download is kept.");
        }
    }

    /** PackageInstaller session: the system shows its own "Install" confirmation, then replaces WygTV. */
    private static void installWithSession(MainActivity a) throws Exception {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        File apk = ApkProvider.file(a);
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("wygtv.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            Intent result = new Intent(a, UpdateResultReceiver.class);
            session.commit(PendingIntent.getBroadcast(a, id, result, flags).getIntentSender());
        } catch (Exception e) {
            try { pi.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        }
    }

    private static void installWithIntent(MainActivity a) {
        Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        i.setDataAndType(ApkProvider.uri(a), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(i); }
        catch (Exception e) { Menus.alert(a, "This TV couldn't open the installer: " + e.getMessage()); }
    }

    /** Words for the installer's result codes. */
    static String failureText(int status, String detail) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "This update can't install over the " + Brand.NAME + " you have, because it was signed differently. Back up in Settings, uninstall " + Brand.NAME + " once, install it again from the Downloader code, then restore.";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "There isn't enough space on this TV for the update.";
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "The update was cancelled.";
            default:
                return "The update didn't install" + (detail == null || detail.isEmpty() ? "." : ": " + detail);
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", Brand.NAME);
        if (c.getResponseCode() != 200) { c.disconnect(); throw new Exception("HTTP " + c.getResponseCode()); }
        return c;
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = open(url + "?t=" + System.currentTimeMillis());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
}
