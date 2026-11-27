package com.wygtv.tv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Records why the app closed unexpectedly, so the next start can show it. Android closes the app on an error nobody
 * caught (or when memory runs out); this keeps the error's name, message and where it happened in a small file.
 */
final class Crash {
    private Crash() { }

    private static File file(MainActivity a) { return new File(a.getFilesDir(), "last_crash.txt"); }

    /** Starts recording. Call once when the app starts. */
    static void install(MainActivity a) {
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        final File f = file(a);
        String version = "";
        try { version = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        final String ver = version;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileOutputStream out = new FileOutputStream(f)) {
                StringWriter sw = new StringWriter();
                Runtime rt = Runtime.getRuntime();
                sw.append("WygTV ").append(ver).append(" • ").append(new java.util.Date().toString())
                  .append(" • thread ").append(t.getName())
                  .append(" • memory used ").append(String.valueOf((rt.totalMemory() - rt.freeMemory()) >> 20))
                  .append(" of ").append(String.valueOf(rt.maxMemory() >> 20)).append(" MB\n");
                e.printStackTrace(new PrintWriter(sw));
                String text = sw.toString();
                if (text.length() > 6000) text = text.substring(0, 6000);
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) { }
            if (before != null) before.uncaughtException(t, e);
        });
    }

    /** If the app closed unexpectedly last time, shows what happened (once), so it can be reported and fixed. */
    static void showLast(MainActivity a) {
        File f = file(a);
        if (!f.exists()) return;
        String text = "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 8000)];
            int n = in.read(b);
            if (n > 0) text = new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable ignored) { }
        f.delete();
        if (text.trim().isEmpty()) return;
        String[] lines = text.split("\n");
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < lines.length && i < 14; i++) shown.append(lines[i].trim()).append('\n');
        final String full = text;
        new Dialogs.Builder(a).setTitle("WygTV closed unexpectedly last time")
                .setMessage("Take a photo of this so it can be fixed:\n\n" + shown)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("WygTV crash", full));
                })
                .show();
    }
}
