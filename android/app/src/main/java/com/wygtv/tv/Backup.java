package com.wygtv.tv;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings → Back up / Restore. A backup is one JSON file, "WygTV-backup-YYYY-MM-DD_HH-MM.json", holding every
 * saved preference: playlists, favorites (every playlist), your own Live TV categories, My List, hidden items,
 * Recently watched and all settings (theme, app mode, start-up, guide choice). It is written to Download/WygTV
 * (so it can be copied off the TV) and also kept inside the app.
 */
final class Backup {
    static final int REQ_BACKUP = 41, REQ_RESTORE = 42, REQ_PICK = 43;
    private static final String PREFIX = "WygTV-backup-";

    private Backup() { }

    // ------------------------------------------------------------------ back up

    static void backup(MainActivity a) {
        if (needsPermission(a)) { a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_BACKUP); return; }
        String name = PREFIX + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(new Date()) + ".json";
        String text;
        try { text = export(a.prefs).toString(2); } catch (Exception e) { Menus.alert(a, "Couldn't build the backup."); return; }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        boolean inApp = write(new File(privateDir(a), name), bytes);
        File shared = new File(sharedDir(), name);
        boolean outside = false;
        if (canUseShared(a)) { File dir = sharedDir(); dir.mkdirs(); outside = dir.isDirectory() && write(shared, bytes); }
        if (!inApp && !outside) { Menus.alert(a, "Couldn't save the backup."); return; }
        String what;
        try { what = summary(new JSONObject(text).getJSONObject("prefs")); } catch (Exception e) { what = "Everything"; }
        new Dialogs.Builder(a).setTitle("Backup saved")
                .setMessage(what + ".\n\n"
                        + (outside ? "File: " + shared.getAbsolutePath() : "Saved inside the app as " + name + " (storage permission was not given, so it isn't in Downloads).")
                        + "\n\nThe file contains your playlist logins, so keep it private.")
                .setPositiveButton("OK", null).show();
    }

    /** All saved preferences, with their types, as JSON. */
    static JSONObject export(SharedPreferences prefs) throws Exception {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject item = new JSONObject();
            if (v instanceof String) item.put("t", "s").put("v", v);
            else if (v instanceof Boolean) item.put("t", "b").put("v", v);
            else if (v instanceof Integer) item.put("t", "i").put("v", v);
            else if (v instanceof Long) item.put("t", "l").put("v", v);
            else if (v instanceof Float) item.put("t", "f").put("v", ((Float) v).doubleValue());
            else if (v instanceof Set) { JSONArray arr = new JSONArray(); for (Object s : (Set<?>) v) arr.put(String.valueOf(s)); item.put("t", "set").put("v", arr); }
            else continue;
            values.put(e.getKey(), item);
        }
        return new JSONObject().put("app", "WygTV").put("format", 1)
                .put("created", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).put("prefs", values);
    }

    // ------------------------------------------------------------------ restore

    static void restore(MainActivity a) {
        if (needsPermission(a)) { a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_RESTORE); return; }
        List<File> files = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (File dir : new File[]{sharedDir(), privateDir(a)}) {
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) if (f.getName().startsWith(PREFIX) && f.getName().endsWith(".json") && names.add(f.getName())) files.add(f);
        }
        java.util.Collections.sort(files, (x, y) -> y.getName().compareTo(x.getName()));
        List<String> labels = new ArrayList<>();
        for (File f : files) labels.add(label(f.getName()) + (f.getParentFile() != null && f.getParentFile().equals(sharedDir()) ? "   (Downloads)" : ""));
        Intent pick = pickIntent();
        boolean canPick = pick.resolveActivity(a.getPackageManager()) != null;
        if (canPick) labels.add("Choose a file from another folder…");
        if (labels.isEmpty()) { Menus.alert(a, "No backups found. Put a " + PREFIX + "….json file in Download/WygTV, or make one with Back up."); return; }
        new Dialogs.Builder(a).setTitle("Restore which backup?").setItems(labels.toArray(new String[0]), (d, w) -> {
            if (w >= files.size()) { a.startActivityForResult(pick, REQ_PICK); return; }
            File f = files.get(w);
            try (InputStream in = new FileInputStream(f)) { confirm(a, read(in), label(f.getName())); }
            catch (Exception e) { Menus.alert(a, "Couldn't read that backup."); }
        }).setNegativeButton("Cancel", null).show();
    }

    static void onPicked(MainActivity a, Uri uri) {
        if (uri == null) return;
        try (InputStream in = a.getContentResolver().openInputStream(uri)) { confirm(a, read(in), "this backup"); }
        catch (Exception e) { Menus.alert(a, "Couldn't read that file."); }
    }

    private static void confirm(MainActivity a, String text, String label) {
        JSONObject backup;
        try { backup = new JSONObject(text); } catch (Exception e) { Menus.alert(a, "That file isn't a WygTV backup."); return; }
        JSONObject values = backup.optJSONObject("prefs");
        if (!"WygTV".equals(backup.optString("app")) || values == null) { Menus.alert(a, "That file isn't a WygTV backup."); return; }
        new Dialogs.Builder(a).setTitle("Restore " + label + "?")
                .setMessage("This replaces what is on this TV with the backup: " + summary(values) + ".")
                .setPositiveButton("Restore", (d, w) -> {
                    try { apply(a.prefs, values); } catch (Exception e) { Menus.alert(a, "Restore failed. Nothing was changed."); return; }
                    a.afterRestore();
                    Toast.makeText(a, "Backup restored", Toast.LENGTH_LONG).show();
                }).setNegativeButton("Cancel", null).show();
    }

    /** Replaces every preference with the backup's (validated first, so a bad file changes nothing). */
    static void apply(SharedPreferences prefs, JSONObject values) throws Exception {
        SharedPreferences.Editor e = prefs.edit().clear();
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject item = values.getJSONObject(k);
            switch (item.getString("t")) {
                case "s": e.putString(k, item.getString("v")); break;
                case "b": e.putBoolean(k, item.getBoolean("v")); break;
                case "i": e.putInt(k, item.getInt("v")); break;
                case "l": e.putLong(k, item.getLong("v")); break;
                case "f": e.putFloat(k, (float) item.getDouble("v")); break;
                case "set": {
                    JSONArray arr = item.getJSONArray("v");
                    Set<String> s = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) s.add(arr.getString(i));
                    e.putStringSet(k, s);
                    break;
                }
                default: break;
            }
        }
        e.commit();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * What a backup holds, counted from its saved values, e.g. "2 playlists, 14 favorites, 3 of your categories,
     * 5 in My List, 7 hidden items and all settings (theme, app mode, start-up, TV guide choice)".
     */
    static String summary(JSONObject values) {
        int playlists = arrayLength(values, "playlists"), categories = arrayLength(values, "virtual_categories"), favorites = 0, myList = 0, hidden = 0;
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject item = values.optJSONObject(k);
            if (item == null || !k.startsWith("account_")) continue;
            if (k.contains("_fav_") && "s".equals(item.optString("t")) && !item.optString("v").isEmpty()) favorites++;
            else if (k.contains("_mylist_")) myList += arrayLength(values, k);
            else if ((k.contains("_hidden_categories_") || k.contains("_hidden_channels_")) && "set".equals(item.optString("t"))) {
                JSONArray arr = item.optJSONArray("v");
                hidden += arr == null ? 0 : arr.length();
            }
        }
        return count(playlists, "playlist", "playlists") + ", " + count(favorites, "favorite", "favorites") + ", "
                + count(categories, "own category", "own categories") + ", "
                + myList + " in My List, " + count(hidden, "hidden item", "hidden items")
                + " and all settings (theme, app mode, start-up, TV guide choice)";
    }

    private static String count(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    /** Length of a JSON array saved as a string value; 0 when missing or unreadable. */
    private static int arrayLength(JSONObject values, String key) {
        JSONObject item = values.optJSONObject(key);
        if (item == null) return 0;
        try { return new JSONArray(item.optString("v", "[]")).length(); } catch (Exception e) { return 0; }
    }

    /** "WygTV-backup-2026-11-21_16-54.json" → "2026-11-21 16:54". */
    static String label(String name) {
        String core = name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name;
        if (core.endsWith(".json")) core = core.substring(0, core.length() - 5);
        int us = core.indexOf('_');
        return us < 0 ? core : core.substring(0, us) + " " + core.substring(us + 1).replace('-', ':');
    }

    private static Intent pickIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        return i;
    }

    private static boolean needsPermission(MainActivity a) {
        return Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && !a.prefs.getBoolean("storage_permission_asked", false) && markAsked(a);
    }

    /** Ask once; after that, carry on with the in-app copy if storage stays off. */
    private static boolean markAsked(MainActivity a) { a.prefs.edit().putBoolean("storage_permission_asked", true).apply(); return true; }

    private static boolean canUseShared(MainActivity a) {
        return Build.VERSION.SDK_INT < 23 || a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private static File sharedDir() { return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WygTV"); }

    private static File privateDir(MainActivity a) { File d = new File(a.getFilesDir(), "backups"); d.mkdirs(); return d; }

    private static boolean write(File f, byte[] bytes) {
        try (OutputStream out = new FileOutputStream(f)) { out.write(bytes); return true; } catch (Exception e) { return false; }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > 20_000_000) throw new IllegalStateException("too big"); }
        return out.toString("UTF-8");
    }
}
