package com.wygtv.tv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Desktop stand-in for the Android app's Library: only the download the shared HDHomeRun code
 * (Hdhr.java, compiled in from the Android app) needs.
 */
final class Library {
    private Library() { }

    static String download(String url, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "WygOTA/1.0 (Linux)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
