package com.wygtv.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Channel logos (Xtream stream_icon / M3U tvg-logo) shown at the left of channel rows. Logos are downloaded on a
 * small thread pool, shrunk, kept in memory and on disk; until one arrives (or when there is none) the row shows an
 * empty space the size of a logo, so names stay lined up.
 */
final class ChannelIcons {
    private static final int MAX_PX = 160;
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** Which logo each row is waiting for, so a late download never lands on a reused or rebuilt row. */
    private static final Map<TextView, String> WANTED = new WeakHashMap<>();
    /** Logos that failed recently, so a broken link isn't fetched for every row redraw. */
    private static final java.util.Set<String> FAILED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private ChannelIcons() { }

    /** Puts the channel's logo (or an empty space) at the start of the row. */
    static void attach(TextView row, String url, String name, int heightDp) {
        Context c = row.getContext();
        int h = dp(c, heightDp), w = h * 4 / 3;
        String key = url == null ? "" : url.trim();
        WANTED.put(row, key);
        Bitmap cached = key.isEmpty() ? null : MEMORY.get(key);
        setIcon(row, cached != null ? fit(c, cached, w, h) : badge(c, name, w, h), c);
        if (cached != null || key.isEmpty() || !(key.startsWith("http://") || key.startsWith("https://")) || FAILED.contains(key)) return;
        File disk = new File(c.getCacheDir(), "logos/" + M3u.idFor(key) + ".png");
        POOL.execute(() -> {
            Bitmap b = load(disk, key);
            if (b == null) { FAILED.add(key); return; }
            MEMORY.put(key, b);
            UI.post(() -> { if (key.equals(WANTED.get(row))) setIcon(row, fit(c, b, w, h), c); });
        });
    }

    private static void setIcon(TextView row, Drawable d, Context c) {
        row.setCompoundDrawables(d, null, null, null);
        row.setCompoundDrawablePadding(dp(c, 12));
    }

    private static Bitmap load(File disk, String url) {
        try {
            if (disk.exists()) { Bitmap b = BitmapFactory.decodeFile(disk.getAbsolutePath()); if (b != null) return b; }
            HttpURLConnection conn = Library.open(url, 15000);
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > 3_000_000) return null; }
                bytes = out.toByteArray();
            } finally { conn.disconnect(); }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= MAX_PX && o.outHeight / (sample * 2) >= MAX_PX / 2) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (b == null) return null;
            if (b.getWidth() > MAX_PX) b = Bitmap.createScaledBitmap(b, MAX_PX, Math.max(1, b.getHeight() * MAX_PX / b.getWidth()), true);
            File dir = disk.getParentFile();
            if (dir != null) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(disk)) { b.compress(Bitmap.CompressFormat.PNG, 100, out); } catch (Exception ignored) { }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The logo scaled to fit a w×h box (keeping its shape), centred. */
    private static Drawable fit(Context c, Bitmap b, int w, int h) {
        Bitmap box = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(box);
        float s = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        cv.drawBitmap(b, null, new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        BitmapDrawable d = new BitmapDrawable(c.getResources(), box);
        d.setBounds(0, 0, w, h);
        return d;
    }

    /**
     * No logo (or not loaded yet): an empty space the size of a logo, so channel names stay lined up.
     * Eddie asked for the logo or nothing, never a box with a letter or number in it.
     */
    private static Drawable badge(Context c, String name, int w, int h) {
        android.graphics.drawable.ColorDrawable d = new android.graphics.drawable.ColorDrawable(0);
        d.setBounds(0, 0, w, h);
        return d;
    }

    private static int dp(Context c, int n) { return (int) (c.getResources().getDisplayMetrics().density * n + .5f); }
}
