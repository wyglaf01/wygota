package com.wygtv.tv;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV guide on the right half of Live TV: a time header (now and the next half hours) over one row per channel,
 * with the channel's logo and name and its programs laid out across the next 90 minutes. The focused channel's
 * now/next details show in the status line. Guide data is fetched a few rows ahead of the focused row and kept for
 * a while, so going back to a category shows it straight away.
 */
final class MiniGuide {
    private static final long KEEP_MS = 30 * 60_000L;
    private static final long WINDOW = 4 * EpgGrid.SLOT;
    private static final int AHEAD = 14;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final class Entry {
        final List<EpgGrid.Program> programs; final long fetched;
        Entry(List<EpgGrid.Program> programs, long fetched) { this.programs = programs; this.fetched = fetched; }
    }
    private static final class Row {
        final MainActivity a; final JSONObject item; final String key; final LinearLayout cells;
        Row(MainActivity a, JSONObject item, String key, LinearLayout cells) { this.a = a; this.item = item; this.key = key; this.cells = cells; }
    }
    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    /** Each row on screen and what it shows, so late guide data never lands on a row that was rebuilt. */
    private static final Map<View, Row> ROWS = new WeakHashMap<>();
    private static LinearLayout header;
    private static long winStart;
    private static Runnable ticker;

    private MiniGuide() { }

    /** Forget all guide data, e.g. after switching playlists. */
    static void clear() { CACHE.clear(); }

    interface ItemAction { void run(JSONObject item); }

    /** Fills the channel list with guide rows for items[from, limit), and puts the time header above the list. */
    static void fill(MainActivity a, LinearLayout list, JSONArray items, int from, int limit, ItemAction ok, ItemAction more) {
        winStart = EpgGrid.windowStart(System.currentTimeMillis());
        addHeader(a, list);
        List<View> rows = new ArrayList<>();
        List<JSONObject> data = new ArrayList<>();
        for (int i = from; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            View row = addRow(a, list, item, it -> { if (!LivePreview.previewOnOk(a, it)) ok.run(it); }, more);
            OPEN.put(row, ok);
            rows.add(row);
            data.add(item);
        }
        for (int i = 0; i < rows.size(); i++) {
            final int at = i;
            View row = rows.get(i);
            row.setOnFocusChangeListener((v, f) -> {
                row.setBackground(rowBg(a, f));
                Row r = ROWS.get(row);
                if (r != null) render(r, f);
                if (!f) return;
                if (touched != null && touched != row) {
                    touched.setBackground(rowBg(a, false));
                    Row old = ROWS.get(touched);
                    if (old != null) render(old, false);
                }
                touched = null;
                showDetails(a, data.get(at));
                LivePreview.show(a, data.get(at));
                for (int k = Math.max(0, at - 2); k < Math.min(rows.size(), at + AHEAD); k++) request(a, data.get(k));
            });
        }
        for (int k = 0; k < Math.min(rows.size(), AHEAD); k++) request(a, data.get(k));
        startTicker();
    }

    /** The row last tapped by its channel name (touch screens have no focus highlight). */
    private static View touched;
    /** What OK on each row does (open full screen), for a tap on a show. */
    private static final Map<View, ItemAction> OPEN = new WeakHashMap<>();

    private static void openRow(MainActivity a, JSONObject item) {
        for (Map.Entry<View, Row> e : ROWS.entrySet()) {
            if (e.getValue().item == item) { ItemAction ok = OPEN.get(e.getKey()); if (ok != null) ok.run(item); return; }
        }
    }

    /** Tap on a channel name: highlight the row and preview it straight away. */
    private static void touchPreview(View row) {
        Row r = ROWS.get(row);
        if (r == null) return;
        if (touched != null && touched != row) {
            touched.setBackground(rowBg(r.a, false));
            Row old = ROWS.get(touched);
            if (old != null) render(old, false);
        }
        touched = row;
        row.setBackground(rowBg(r.a, true));
        render(r, true);
        showDetails(r.a, r.item);
        LivePreview.previewNow(r.a, r.item);
        request(r.a, r.item);
    }

    /** The channel a guide row shows, or null for any other view. */
    static JSONObject itemOf(View v) { Row r = ROWS.get(v); return r == null ? null : r.item; }

    private static void addHeader(MainActivity a, LinearLayout list) {
        if (!(list.getParent() instanceof View) || !(((View) list.getParent()).getParent() instanceof ViewGroup)) return;
        View scroll = (View) list.getParent();
        ViewGroup detail = (ViewGroup) scroll.getParent();
        header = new LinearLayout(a);
        header.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(HEADER_DP));
        lp.setMargins(a.dp(5), 0, a.dp(5), 0);
        detail.addView(header, detail.indexOfChild(scroll), lp);
        drawHeader(a);
    }

    private static void drawHeader(MainActivity a) {
        if (header == null) return;
        header.removeAllViews();
        DateFormat tf = android.text.format.DateFormat.getTimeFormat(a);
        TextView today = a.label("Today", 15);
        today.setTextColor(Theme.MUTED);
        header.addView(today, new LinearLayout.LayoutParams(a.dp(CHANNEL_DP - 6), -1));
        for (long t = winStart; t < winStart + WINDOW; t += EpgGrid.SLOT) {
            TextView v = a.label(tf.format(new Date(t)), 15);
            v.setTextColor(Theme.ACCENT);
            v.setTypeface(null, Typeface.BOLD);
            header.addView(v, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private static final int CHANNEL_DP = 200, ROW_DP = 48, HEADER_DP = 30;

    /** Height in dp of the guide area showing this many channel rows. */
    static int height(int rows) { return HEADER_DP + Math.max(3, Math.min(5, rows)) * (ROW_DP + 4) + 6; }

    private static View addRow(MainActivity a, LinearLayout list, JSONObject item, ItemAction ok, ItemAction more) {
        String title = item.optString("name", "Untitled");
        LinearLayout row = new LinearLayout(a);
        row.setFocusable(true);
        row.setClickable(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4));
        row.setBackground(rowBg(a, false));
        row.setTag(item.optString("stream_id"));
        row.setOnClickListener(v -> ok.run(item));
        row.setOnLongClickListener(v -> { more.run(item); return true; });
        // Touch: tapping the channel name previews it; tapping a show opens it full screen. Hold either for options.
        View.OnLongClickListener hold = v -> { more.run(item); return true; };
        // The channel's logo, its name and (small, underneath) its category.
        boolean hiddenCh = GuideOptions.isHidden(a, item);
        String top = starred(a, item) + title, sub = hiddenCh ? "Hidden" : categoryOf(a, item);
        TextView name = a.label(top, 15);
        name.setMaxLines(2);
        if (!sub.isEmpty()) {
            android.text.SpannableString two = new android.text.SpannableString(top + "\n" + sub);
            two.setSpan(new android.text.style.RelativeSizeSpan(.75f), top.length() + 1, two.length(), 0);
            two.setSpan(new android.text.style.ForegroundColorSpan(Theme.MUTED), top.length() + 1, two.length(), 0);
            two.setSpan(new android.text.style.StyleSpan(Typeface.NORMAL), top.length() + 1, two.length(), 0);
            name.setLineSpacing(0, .9f);
            name.setText(two);
        }
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(null, Typeface.BOLD);
        ChannelIcons.attach(name, item.optString("stream_icon"), title, 28);
        if (hiddenCh) name.setAlpha(.45f);
        name.setClickable(true);
        name.setOnClickListener(v -> touchPreview(row));
        name.setOnLongClickListener(hold);
        row.addView(name, new LinearLayout.LayoutParams(a.dp(CHANNEL_DP - 6), -1));
        LinearLayout cells = new LinearLayout(a);
        cells.setClickable(true);
        cells.setOnClickListener(v -> openRow(a, item));
        cells.setOnLongClickListener(hold);
        row.addView(cells, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(ROW_DP));
        lp.setMargins(a.dp(5), a.dp(2), a.dp(5), a.dp(2));
        list.addView(row, lp);
        Row r = new Row(a, item, key(a, item), cells);
        ROWS.put(row, r);
        render(r, false);
        return row;
    }

    /** The category this channel belongs to in its playlist, or "" when it isn't known here. */
    private static String categoryOf(MainActivity a, JSONObject item) {
        String pid = item.optString("playlist"), cid = item.optString("category_id");
        if (cid.isEmpty() || !(pid.isEmpty() || pid.equals(a.library.activeId()))) return "";
        for (int k = 0; k < a.categories.length(); k++) {
            JSONObject c = a.categories.optJSONObject(k);
            if (c != null && cid.equals(c.optString("category_id"))) return c.optString("category_name");
        }
        return "";
    }

    private static String starred(MainActivity a, JSONObject item) {
        if ("fav".equals(a.category)) return ""; // Showing Favorites: every row is one.
        String pid = item.optString("playlist");
        boolean fav = (pid.isEmpty() || pid.equals(a.library.activeId())) && a.isFavorite("live", item.optString("stream_id"));
        return fav ? "★ " : "";
    }

    /** Playlist + channel id, since two providers can use the same channel number. */
    private static String key(MainActivity a, JSONObject item) {
        String pid = item.optString("playlist");
        return (pid.isEmpty() ? a.library.activeId() : pid) + "|" + item.optString("stream_id");
    }

    private static void request(MainActivity a, JSONObject item) {
        String id = key(a, item), sid = item.optString("stream_id");
        Entry have = CACHE.get(id);
        long now = System.currentTimeMillis();
        if (sid.isEmpty() || (have != null && now - have.fetched < KEEP_MS && !runOut(have, now)) || !LOADING.add(id)) return;
        String pid = item.optString("playlist");
        final JSONObject other = pid.isEmpty() || pid.equals(a.library.activeId()) ? null : a.library.find(pid);
        final String action = "get_short_epg&stream_id=" + a.enc(sid) + "&limit=8";
        POOL.execute(() -> {
            List<EpgGrid.Program> progs = new ArrayList<>();
            try {
                JSONArray l = (other == null ? a.apiBlocking(action) : a.library.epgFor(other, action)).optJSONArray("epg_listings");
                TimeZone tz = TimeZone.getDefault();
                if (l != null) for (int k = 0; k < l.length(); k++) {
                    JSONObject e = l.optJSONObject(k);
                    if (e == null) continue;
                    long start = EpgGrid.parseTime(e.optString("start_timestamp"), e.optString("start"), tz);
                    long end = EpgGrid.parseTime(e.optString("stop_timestamp"), e.optString("end", e.optString("stop")), tz);
                    progs.add(new EpgGrid.Program(start, end, a.epgText(e.optString("title")), ""));
                }
            } catch (Exception ignored) {
                // No guide for this channel; the row says so and the channel still plays.
            }
            List<EpgGrid.Program> clean = EpgGrid.clean(progs);
            UI.post(() -> {
                LOADING.remove(id);
                CACHE.put(id, new Entry(clean, System.currentTimeMillis()));
                for (Map.Entry<View, Row> r : new ArrayList<>(ROWS.entrySet())) {
                    if (!id.equals(r.getValue().key) || !r.getKey().isAttachedToWindow()) continue;
                    render(r.getValue(), r.getKey().hasFocus() || r.getKey() == touched);
                    if (r.getKey().hasFocus()) showDetails(r.getValue().a, r.getValue().item);
                }
            });
        });
    }

    private static boolean runOut(Entry e, long now) {
        return !e.programs.isEmpty() && e.programs.get(e.programs.size() - 1).end <= now;
    }

    /** Program blocks across the window, sized by their length. */
    private static void render(Row r, boolean focused) {
        MainActivity a = r.a;
        r.cells.removeAllViews();
        Entry e = CACHE.get(r.key);
        long winEnd = winStart + WINDOW, now = System.currentTimeMillis();
        if (e == null || e.programs.isEmpty()) {
            TextView v = cell(a, e == null ? "Loading guide…" : "No guide information", false, focused);
            v.setTextColor(Theme.MUTED);
            r.cells.addView(v, cellLp(a, 1));
            return;
        }
        for (EpgGrid.Cell c : EpgGrid.cells(e.programs, winStart, winEnd)) {
            boolean airing = c.program != null && c.contains(now);
            String t = c.program == null ? "" : (c.program.title.isEmpty() ? "Untitled" : c.program.title);
            r.cells.addView(cell(a, t, airing, focused), cellLp(a, (int) Math.max(1, (c.end - c.start) / EpgGrid.MINUTE)));
        }
    }

    private static TextView cell(MainActivity a, String text, boolean airing, boolean rowFocused) {
        TextView v = a.label(text, 14);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(a.dp(8), 0, a.dp(6), 0);
        GradientDrawable g = new GradientDrawable();
        int base = airing ? Theme.AIRING : Theme.PANEL;
        g.setColor(Theme.pane(Theme.alpha(rowFocused ? Theme.mix(base, Theme.FOCUS, 0.45f) : base, 0xe6)));
        g.setCornerRadius(a.dp(6));
        g.setStroke(a.dp(1), airing ? Theme.mix(Theme.STROKE, Theme.ACCENT, 0.4f) : Theme.edge(false));
        v.setBackground(g);
        return v;
    }

    private static LinearLayout.LayoutParams cellLp(MainActivity a, int weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, weight);
        lp.setMargins(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
        return lp;
    }

    private static GradientDrawable rowBg(MainActivity a, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.pane(focused ? Theme.alpha(Theme.FOCUS, 0xd0) : Theme.alpha(Theme.mix(Theme.BG, Theme.PANEL, 0.4f), 0xe0)));
        g.setCornerRadius(a.dp(8));
        g.setStroke(a.dp(focused ? 2 : 1), focused ? Theme.STROKE_FOCUS : Theme.edge(false));
        return g;
    }

    /** "Now" and "Next" for the focused channel in the status line under the guide. */
    private static void showDetails(MainActivity a, JSONObject item) {
        if (a.status == null) return;
        String name = item.optString("name", "Untitled");
        Entry e = CACHE.get(key(a, item));
        long now = System.currentTimeMillis();
        if (e == null) { a.status.setText(name + "  •  loading guide…"); return; }
        EpgGrid.Program cur = EpgGrid.at(e.programs, now), next = null;
        for (EpgGrid.Program p : e.programs) if (p.start >= (cur == null ? now : cur.end)) { next = p; break; }
        DateFormat tf = android.text.format.DateFormat.getTimeFormat(a);
        StringBuilder s = new StringBuilder(name);
        if (cur == null && next == null) s.append("  •  no guide information");
        if (cur != null) s.append("  •  Now: ").append(cur.title.isEmpty() ? "Untitled" : cur.title).append("  ")
                .append(tf.format(new Date(cur.start))).append("–").append(tf.format(new Date(cur.end)))
                .append(" (").append(EpgGrid.minutesLeft(cur, now)).append(" min left)");
        if (next != null) s.append("  •  Next: ").append(tf.format(new Date(next.start))).append("  ").append(next.title.isEmpty() ? "Untitled" : next.title);
        a.status.setText(s);
    }

    /** Once a minute: move the window on at each half hour and re-draw the rows on screen. */
    private static void startTicker() {
        if (ticker != null) return;
        ticker = new Runnable() {
            @Override public void run() {
                boolean any = false;
                long start = EpgGrid.windowStart(System.currentTimeMillis());
                boolean moved = start != winStart;
                winStart = start;
                for (Map.Entry<View, Row> r : new ArrayList<>(ROWS.entrySet())) {
                    if (!r.getKey().isAttachedToWindow()) continue;
                    if (!any && moved && header != null && header.isAttachedToWindow()) drawHeader(r.getValue().a);
                    any = true;
                    render(r.getValue(), r.getKey().hasFocus() || r.getKey() == touched);
                    Entry e = CACHE.get(r.getValue().key);
                    if (e != null && runOut(e, System.currentTimeMillis()) && r.getKey().hasFocus()) request(r.getValue().a, r.getValue().item);
                }
                if (any) UI.postDelayed(this, 60_000L); else ticker = null;
            }
        };
        UI.postDelayed(ticker, 60_000L);
    }
}
