package com.wygtv.tv;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Full-screen TV guide: channels down the side, a two-hour EPG timeline across,
 * program info top-left and a live preview of the focused channel top-right.
 * OK opens the channel full screen; Back from the player returns here.
 */
final class TvGuide {
    private static final int PAGE = 40;
    private static final long HORIZON = 24 * 60 * EpgGrid.MINUTE;

    private final MainActivity a;
    private final JSONArray channels;
    /** True when the list holds channels from more than one playlist (Favorites or your own categories); rows then name their playlist. */
    private final boolean mixed;
    private final String categoryTitle;
    private final Map<String, List<EpgGrid.Program>> epg = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SparseArray<LinearLayout> rowCells = new SparseArray<>();
    private final SparseArray<TextView> rowNames = new SparseArray<>();

    private long winStart;
    private int page, focusRow, session, chanW, timelineW;
    /** Id of the "Showing" button at the top right: Up from the first row and Right from the top of the side menu go there. */
    private int sourceId = View.NO_ID;
    private long focusTime;
    private boolean rebuilding;
    private View shownRoot;
    private ExecutorService pool;
    private ExoPlayer preview;
    private String previewId, previewName;
    private Runnable pendingPreview, ticker;
    private java.text.DateFormat timeFmt;

    private PlayerView previewView;
    private TextView clock, infoChannel, infoSource, infoTitle, infoTime, infoLeft, infoDesc, infoNext, previewLabel, previewError;
    private ProgressBar progress;
    private LinearLayout timeHeader, rows;
    private View nowLine;

    static void open(MainActivity a) { open(a, null); }

    /** Opens the guide on the current channel list, starting on the given channel when there is one. */
    static void open(MainActivity a, String focusStreamId) {
        String title = "";
        for (int i = 0; i < a.categories.length(); i++) {
            JSONObject c = a.categories.optJSONObject(i);
            if (c != null && c.optString("category_id").equals(a.category)) title = c.optString("category_name");
        }
        a.tvGuide = new TvGuide(a, a.items, title);
        if (focusStreamId != null) a.tvGuide.focusStream(focusStreamId);
        a.tvGuide.show();
    }

    private TvGuide(MainActivity a, JSONArray channels, String categoryTitle) {
        this.a = a;
        this.channels = channels;
        this.categoryTitle = categoryTitle;
        java.util.Set<String> from = new java.util.HashSet<>();
        for (int i = 0; i < channels.length(); i++) { JSONObject o = channels.optJSONObject(i); if (o != null) from.add(o.optString("playlist").isEmpty() ? a.library.activeId() : o.optString("playlist")); }
        mixed = from.size() > 1;
        winStart = EpgGrid.windowStart(now());
        focusTime = now();
    }

    private static long now() { return System.currentTimeMillis(); }

    // ---------------------------------------------------------------- screen

    void show() {
        a.mode = "guide";
        String single = "";
        if (!mixed && a.library.inUse().length() > 1) {
            JSONObject first = channels.optJSONObject(0);
            JSONObject other = first == null ? null : otherPlaylist(0);
            single = "  •  " + Library.nameOf(other != null ? other : a.library.find(a.library.activeId()));
        }
        a.shell((categoryTitle.isEmpty() ? "TV GUIDE" : "TV GUIDE • " + categoryTitle) + single);
        shownRoot = a.root;
        session++;
        pool = Executors.newFixedThreadPool(3);
        timeFmt = android.text.format.DateFormat.getTimeFormat(a);
        long n = now();
        if (focusTime < n) focusTime = n;
        if (winStart + EpgGrid.WINDOW <= n || winStart < EpgGrid.windowStart(n)) winStart = EpgGrid.windowStart(n);

        // Tighter header and footer than the list screens so more channel rows fit on a TV.
        View header = a.root.getChildAt(0);
        a.root.removeViewAt(0);
        ((TextView) header).setTextSize(24);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(header, new LinearLayout.LayoutParams(0, -1, 1));
        // Pick what the guide lists: Favorites, all channels or one category (Up from the grid reaches it).
        TextView source = a.button("☰  Showing: " + (categoryTitle.isEmpty() ? "All channels" : categoryTitle), bar, a::chooseGuideSource);
        source.setTextSize(15);
        source.setSingleLine(true);
        source.setPadding(a.dp(14), 0, a.dp(14), 0);
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-2, a.dp(38));
        sourceLp.setMargins(a.dp(8), 0, a.dp(12), 0);
        source.setLayoutParams(sourceLp);
        source.setId(View.generateViewId());
        sourceId = source.getId();
        source.setTag(NavRail.TOP_TARGET);
        // An antenna tuner's guide: hide or show its channels (hidden ones are left out everywhere in the app).
        if (Antenna.isAntenna(a.category)) {
            final String src = a.category;
            final JSONObject tuner = a.library.find(src.substring(Antenna.PREFIX.length()));
            if (tuner != null) {
                TextView hide = a.button("⊘  Hide / show channels", bar, () -> Manage.antennaChannels(a, tuner, () -> Antenna.openGuide(a, src, null, true)));
                hide.setTextSize(15);
                hide.setSingleLine(true);
                hide.setPadding(a.dp(14), 0, a.dp(14), 0);
                LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(-2, a.dp(38));
                hideLp.setMargins(0, 0, a.dp(12), 0);
                hide.setLayoutParams(hideLp);
            }
        }
        // Channel search (same screen as Live TV → Search): 3+ letters, every playlist, favorites from the results.
        TextView find = a.button("⌕  Search", bar, a::searchLive);
        find.setTextSize(15);
        find.setSingleLine(true);
        find.setPadding(a.dp(14), 0, a.dp(14), 0);
        LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(-2, a.dp(38));
        findLp.setMargins(0, 0, a.dp(12), 0);
        find.setLayoutParams(findLp);
        clock = text("", 20, Theme.ACCENT, true);
        bar.addView(clock, new LinearLayout.LayoutParams(-2, -1));
        a.root.addView(bar, 0, new LinearLayout.LayoutParams(-1, a.dp(44)));
        a.status.setLayoutParams(new LinearLayout.LayoutParams(-1, a.dp(24)));
        a.status.setText("OK  Watch full screen      ◀ ▶  Move through time      ▲ ▼  Change channel      " + "Hold OK  Favorite, hide, group      " + "▲ from the top  Pick Favorites or a category, or Search      BACK  " + (a.liveOnly() ? "Exit" : "Live TV"));

        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        chanW = a.dp(200);
        timelineW = Math.max(a.dp(320), screenW - a.dp(48) - a.dp(NavRail.COLLAPSED_DP) - chanW - a.dp(8));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(col, new LinearLayout.LayoutParams(-1, -1));
        col.addView(buildTop(), new LinearLayout.LayoutParams(-1, a.dp(190)));

        timeHeader = new LinearLayout(a);
        timeHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams thp = new LinearLayout.LayoutParams(-1, a.dp(24));
        thp.topMargin = a.dp(8);
        col.addView(timeHeader, thp);

        FrameLayout grid = new FrameLayout(a);
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        grid.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        nowLine = new View(a);
        nowLine.setBackgroundColor(Theme.COPPER);
        grid.addView(nowLine, new FrameLayout.LayoutParams(a.dp(2), -1));

        final View myRoot = shownRoot;
        myRoot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { v.removeOnAttachStateChangeListener(this); if (v == shownRoot) hide(); }
        });

        buildRows();
        ticker = new Runnable() { @Override public void run() { tick(); ui.postDelayed(this, 30_000); } };
        ticker.run();
    }

    private View buildTop() {
        LinearLayout top = new LinearLayout(a);

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(12));
        info.setBackground(a.bg(a.PANEL));
        top.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
        infoChannel = text("", 12, Theme.MUTED, false);
        infoChannel.setLetterSpacing(.12f);
        info.addView(infoChannel);
        infoSource = text("", 12, Theme.ACCENT, false);
        infoSource.setSingleLine(true);
        infoSource.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(infoSource);
        infoTitle = text("", 22, Theme.TEXT, true);
        infoTitle.setMaxLines(2);
        infoTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(infoTitle);
        LinearLayout timeRow = new LinearLayout(a);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(timeRow, new LinearLayout.LayoutParams(-1, a.dp(24)));
        infoTime = text("", 13, Theme.ACCENT, false);
        timeRow.addView(infoTime);
        progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.COPPER));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Theme.alpha(Theme.TEXT,0x40)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(a.dp(160), a.dp(6));
        pp.setMargins(a.dp(12), 0, a.dp(12), 0);
        timeRow.addView(progress, pp);
        infoLeft = text("", 13, Theme.MUTED, false);
        timeRow.addView(infoLeft);
        infoDesc = text("", 13, Theme.TEXT, false);
        infoDesc.setMaxLines(3);
        infoDesc.setEllipsize(TextUtils.TruncateAt.END);
        infoDesc.setLineSpacing(0, 1.15f);
        info.addView(infoDesc, new LinearLayout.LayoutParams(-1, 0, 1));
        infoNext = text("", 12, Theme.MUTED, false);
        infoNext.setSingleLine(true);
        infoNext.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(infoNext);

        FrameLayout frame = new FrameLayout(a);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0xff000000);
        border.setCornerRadius(a.dp(10));
        border.setStroke(a.dp(3), Theme.STROKE_FOCUS);
        frame.setBackground(border);
        frame.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(a.dp(190) * 16 / 9, -1);
        fp.leftMargin = a.dp(14);
        top.addView(frame, fp);
        previewView = new PlayerView(a);
        previewView.setUseController(false);
        previewView.setFocusable(false);
        frame.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        previewError = text("Preview unavailable", 14, Theme.MUTED, false);
        previewError.setGravity(Gravity.CENTER);
        previewError.setVisibility(View.GONE);
        frame.addView(previewError, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = text("LIVE", 11, 0xffFFFFFF, true);
        badge.setBackgroundColor(0xffB3261E);
        badge.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bp.setMargins(a.dp(10), a.dp(10), 0, 0);
        frame.addView(badge, bp);
        previewLabel = text("", 12, 0xffFFFFFF, false);
        previewLabel.setSingleLine(true);
        previewLabel.setEllipsize(TextUtils.TruncateAt.END);
        previewLabel.setPadding(a.dp(10), a.dp(14), a.dp(10), a.dp(6));
        previewLabel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
        frame.addView(previewLabel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return top;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView v = a.label(s, size);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    // ---------------------------------------------------------------- rows

    private void buildRows() {
        rebuilding = true;
        rows.removeAllViews();
        rowCells.clear();
        rowNames.clear();
        int total = channels.length();
        int from = page * PAGE, to = Math.min(total, from + PAGE);
        if (page > 0) navButton("▲ Previous channels", () -> { page--; focusRow = page * PAGE + PAGE - 1; buildRows(); }).setNextFocusUpId(sourceId);
        for (int i = from; i < to; i++) addRow(i);
        if (to < total) navButton("▼ More channels (" + (total - to) + " left)", () -> { page++; focusRow = page * PAGE; buildRows(); });
        if (total == 0) rows.addView(text("No channels in this category.", 18, Theme.TEXT, false), new LinearLayout.LayoutParams(-1, a.dp(60)));
        rebuilding = false;
        drawTimeHeader();
        // Ask for guide data starting at the focused channel, so what you look at fills in first.
        if (focusRow < from || focusRow >= to) focusRow = from;
        for (int k = 0; k < to - from; k++) {
            int i = from + Math.floorMod(focusRow - from + k, to - from);
            requestEpg(i);
        }
        if (to > from) rows.post(() -> focusCell(focusRow, focusTime));
    }

    private TextView navButton(String label, Runnable r) {
        TextView b = a.button(label, rows, r);
        b.setTextSize(16);
        return b;
    }

    /** Up from the top row goes to the buttons at the top right, not into the side menu on the left. */
    private boolean topRow(int i) { return page == 0 && i == 0; }

    private void addRow(int i) {
        JSONObject ch = channels.optJSONObject(i);
        if (ch == null) return;
        LinearLayout row = new LinearLayout(a);
        rows.addView(row, new LinearLayout.LayoutParams(-1, a.dp(40)));
        // Just the channel's logo, its name and (small, underneath) its category; hold OK on a programme for ★ Favorites.
        boolean hiddenCh = GuideOptions.isHidden(a, ch);
        String chLabel = ch.optString("name", "Channel"), sub = hiddenCh ? "Hidden" : subLabel(i);
        TextView name = text(chLabel, 14, Theme.TEXT, true);
        name.setSingleLine(true);
        if (!sub.isEmpty()) {
            android.text.SpannableString two = new android.text.SpannableString(chLabel + "\n" + sub);
            two.setSpan(new android.text.style.RelativeSizeSpan(.72f), chLabel.length() + 1, two.length(), 0);
            two.setSpan(new android.text.style.ForegroundColorSpan(Theme.MUTED), chLabel.length() + 1, two.length(), 0);
            two.setSpan(new android.text.style.StyleSpan(Typeface.NORMAL), chLabel.length() + 1, two.length(), 0);
            name.setSingleLine(false);
            name.setMaxLines(2);
            name.setLineSpacing(0, .9f);
            name.setText(two);
        }
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(a.dp(12), 0, a.dp(8), 0);
        name.setBackground(rowBg(false));
        // Touch: tapping the channel name previews it; tapping a show opens it full screen. Hold for options.
        name.setClickable(true);
        name.setOnClickListener(v -> previewRow(i));
        name.setOnLongClickListener(v -> { options(i); return true; });
        ChannelIcons.attach(name, ch.optString("stream_icon"), ch.optString("name", "Channel"), 24);
        if (hiddenCh) name.setAlpha(.45f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(chanW, -1);
        np.setMargins(0, a.dp(2), a.dp(8), a.dp(2));
        row.addView(name, np);
        LinearLayout cells = new LinearLayout(a);
        row.addView(cells, new LinearLayout.LayoutParams(timelineW, -1));
        rowCells.put(i, cells);
        rowNames.put(i, name);
        fillRow(i);
    }

    private void fillRow(int i) {
        LinearLayout cells = rowCells.get(i);
        if (cells == null) return;
        boolean hadFocus = cells.hasFocus();
        int savedRow = focusRow;
        long savedTime = focusTime;
        boolean wasRebuilding = rebuilding;
        rebuilding = true;
        cells.removeAllViews();
        long winEnd = winStart + EpgGrid.WINDOW;
        List<EpgGrid.Program> progs = epg.get(key(i));
        List<EpgGrid.Cell> list = progs == null
                ? Collections.singletonList(new EpgGrid.Cell(winStart, winEnd, null))
                : EpgGrid.cells(progs, winStart, winEnd);
        float scale = timelineW / (float) EpgGrid.WINDOW;
        for (int k = 0; k < list.size(); k++) {
            EpgGrid.Cell c = list.get(k);
            int x0 = Math.round((c.start - winStart) * scale), x1 = Math.round((c.end - winStart) * scale);
            cells.addView(cellView(i, c, progs == null, k == 0, k == list.size() - 1, x1 - x0), cellParams(x1 - x0));
        }
        rebuilding = wasRebuilding;
        if (hadFocus && !wasRebuilding) focusCell(savedRow, savedTime);
    }

    private LinearLayout.LayoutParams cellParams(int width) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(a.dp(2), width - a.dp(4)), -1);
        lp.setMargins(0, a.dp(2), a.dp(4), a.dp(2));
        return lp;
    }

    private View cellView(int row, EpgGrid.Cell c, boolean loading, boolean first, boolean last, int width) {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(a.dp(8), 0, a.dp(6), 0);
        v.setFocusable(true);
        v.setClickable(true);
        v.setTag(c);
        long winEnd = winStart + EpgGrid.WINDOW;
        EpgGrid.Program p = c.program;
        String title = p == null ? (loading ? "Loading guide…" : "No guide information")
                : (p.start < winStart ? "‹ " : "") + (p.title.isEmpty() ? "Untitled" : p.title) + (p.end > winEnd ? " ›" : "");
        TextView t = text(title, 14, Theme.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(t);
        TextView sub = null;
        if (width > a.dp(90)) {
            sub = text(p == null ? "Press OK to watch" : fmt(p.start) + " – " + fmt(p.end), 11, Theme.MUTED, false);
            sub.setSingleLine(true);
            v.addView(sub);
        }
        final TextView subText = sub;
        boolean airing = p != null && p.start <= now() && now() < p.end;
        v.setBackground(cellBg(false, airing));
        v.setOnFocusChangeListener((view, focused) -> {
            v.setBackground(cellBg(focused, airing));
            t.setTextColor(focused ? Theme.INK : Theme.TEXT);
            if (subText != null) subText.setTextColor(focused ? Theme.mix(Theme.INK,Theme.FOCUS,0.4f) : Theme.MUTED);
            if (focused && !rebuilding) onCellFocused(row, c);
        });
        v.setOnClickListener(view -> {
            // A tap on a show opens it full screen (tap the channel name to preview instead).
            if (view.isInTouchMode()) { playFullScreen(row); return; }
            // "OK only" preview: the first OK previews this channel, OK again opens it full screen.
            if (PreviewDelay.okOnly(a.prefs) && !(preview != null && key(row).equals(previewId)) && !streamId(row).isEmpty()) {
                if (pendingPreview != null) ui.removeCallbacks(pendingPreview);
                startPreview(row);
            } else playFullScreen(row);
        });
        v.setOnLongClickListener(view -> { options(row); return true; });
        if (topRow(row)) v.setNextFocusUpId(sourceId);
        v.setOnKeyListener((view, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && last) {
                if (winStart + EpgGrid.WINDOW < EpgGrid.windowStart(now()) + HORIZON) shiftWindow(row, 2 * EpgGrid.SLOT, c.end);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && first) {
                // At "now" there is nothing earlier to show, so Left falls through to the side menu.
                if (winStart <= EpgGrid.windowStart(now())) return false;
                shiftWindow(row, -2 * EpgGrid.SLOT, c.start - 1);
                return true;
            }
            return false;
        });
        return v;
    }

    private GradientDrawable cellBg(boolean focused, boolean airing) {
        if (focused) return a.homeTileBackground(true);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                airing ? new int[]{Theme.pane(Theme.alpha(Theme.AIRING,0xeb)), Theme.pane(Theme.alpha(Theme.mix(Theme.AIRING,Theme.BG,0.5f),0xeb))} : Theme.surface(false));
        g.setCornerRadius(a.dp(8));
        g.setStroke(a.dp(1), airing ? Theme.mix(Theme.STROKE,Theme.ACCENT,0.4f) : Theme.edge(false));
        return g;
    }

    private GradientDrawable rowBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.pane(focused ? Theme.alpha(Theme.FOCUS,0xbf) : Theme.alpha(Theme.mix(Theme.BG,Theme.PANEL,0.4f),0xe0)));
        g.setCornerRadius(a.dp(8));
        g.setStroke(a.dp(1), focused ? Theme.STROKE_FOCUS : Theme.edge(false));
        return g;
    }

    private void shiftWindow(int row, long by, long target) {
        long min = EpgGrid.windowStart(now());
        winStart = Math.max(min, winStart + by);
        focusRow = row;
        focusTime = target;
        for (int k = 0; k < rowCells.size(); k++) fillRow(rowCells.keyAt(k));
        drawTimeHeader();
        tick();
        focusCell(row, target);
    }

    private void focusCell(int row, long t) {
        LinearLayout cells = rowCells.get(row);
        if (cells == null && rowCells.size() > 0) cells = rowCells.valueAt(0);
        if (cells == null || cells.getChildCount() == 0) return;
        List<EpgGrid.Cell> list = new ArrayList<>();
        for (int k = 0; k < cells.getChildCount(); k++) list.add((EpgGrid.Cell) cells.getChildAt(k).getTag());
        int idx = EpgGrid.indexAt(list, t);
        View v = cells.getChildAt(Math.max(0, idx));
        if (v.isFocused()) onCellFocused(row, list.get(Math.max(0, idx)));
        else v.requestFocus();
    }

    private void onCellFocused(int row, EpgGrid.Cell c) {
        if (row != focusRow) {
            TextView old = rowNames.get(focusRow);
            if (old != null) old.setBackground(rowBg(false));
        }
        TextView name = rowNames.get(row);
        if (name != null) name.setBackground(rowBg(true));
        focusRow = row;
        long n = now();
        focusTime = c.contains(focusTime) ? focusTime : c.contains(n) ? n : c.start;
        showInfo(row, c);
        schedulePreview(row);
    }

    /** Tap on a channel name: highlight that row and preview it straight away. */
    private void previewRow(int row) {
        if (row != focusRow) {
            TextView old = rowNames.get(focusRow);
            if (old != null) old.setBackground(rowBg(false));
        }
        TextView name = rowNames.get(row);
        if (name != null) name.setBackground(rowBg(true));
        focusRow = row;
        long n = now();
        focusTime = n;
        LinearLayout cells = rowCells.get(row);
        if (cells != null && cells.getChildCount() > 0) {
            List<EpgGrid.Cell> list = new ArrayList<>();
            for (int k = 0; k < cells.getChildCount(); k++) list.add((EpgGrid.Cell) cells.getChildAt(k).getTag());
            showInfo(row, list.get(Math.max(0, EpgGrid.indexAt(list, n))));
        }
        if (pendingPreview != null) ui.removeCallbacks(pendingPreview);
        pendingPreview = null;
        startPreview(row);
    }

    private void drawTimeHeader() {
        if (timeHeader == null) return;
        timeHeader.removeAllViews();
        TextView day = text(DateUtils.isToday(winStart) ? "Today" : DateUtils.formatDateTime(a, winStart, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY), 13, Theme.ACCENT, true);
        timeHeader.addView(day, new LinearLayout.LayoutParams(chanW + a.dp(8), -1));
        float scale = timelineW / (float) EpgGrid.WINDOW;
        for (int k = 0; k < 4; k++) {
            long s = winStart + k * EpgGrid.SLOT;
            int w = Math.round((s + EpgGrid.SLOT - winStart) * scale) - Math.round((s - winStart) * scale);
            timeHeader.addView(text(fmt(s), 13, Theme.MUTED, false), new LinearLayout.LayoutParams(w, -1));
        }
    }

    private void tick() {
        long n = now();
        if (clock != null) clock.setText(fmt(n));
        if (nowLine != null) {
            boolean visible = n >= winStart && n < winStart + EpgGrid.WINDOW;
            nowLine.setVisibility(visible ? View.VISIBLE : View.GONE);
            nowLine.setTranslationX(chanW + a.dp(8) + (n - winStart) * (timelineW / (float) EpgGrid.WINDOW) - a.dp(1));
        }
        View f = a.getCurrentFocus();
        if (f != null && f.getTag() instanceof EpgGrid.Cell && rowCells.get(focusRow) == f.getParent()) showInfo(focusRow, (EpgGrid.Cell) f.getTag());
        syncPreviewLabel();
    }

    // ---------------------------------------------------------------- info panel

    private void showInfo(int row, EpgGrid.Cell c) {
        JSONObject ch = channels.optJSONObject(row);
        if (ch == null) return;
        String name = ch.optString("name", "Channel");
        // The channel's logo from the playlist beside its name, not its number.
        infoChannel.setText(name.toUpperCase(java.util.Locale.getDefault()));
        ChannelIcons.attach(infoChannel, ch.optString("stream_icon"), name, 22);
        previewLabel.setText(name + " · live preview");
        String pid = ch.optString("playlist");
        String source = Library.sourceLabel(a.library.find(pid.isEmpty() ? a.library.activeId() : pid));
        infoSource.setText(source.isEmpty() ? "" : "Source  " + source);
        infoSource.setVisibility(source.isEmpty() ? View.GONE : View.VISIBLE);
        List<EpgGrid.Program> progs = epg.get(key(row));
        EpgGrid.Program p = c.program;
        long n = now();
        if (p == null) {
            infoTitle.setText(name);
            infoTime.setText(progs == null ? "Loading guide…" : "No guide information for this time");
            progress.setVisibility(View.GONE);
            infoLeft.setText("");
            infoDesc.setText("");
            EpgGrid.Program nx = null;
            if (progs != null) for (EpgGrid.Program q : progs) if (q.start >= c.start) { nx = q; break; }
            infoNext.setText(nx == null ? "" : "Next at " + when(nx.start) + " · " + nx.title);
            return;
        }
        infoTitle.setText(p.title.isEmpty() ? "Untitled" : p.title);
        infoTime.setText(when(p.start) + " – " + fmt(p.end));
        if (p.start <= n && n < p.end) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(EpgGrid.progressPercent(p, n));
            infoLeft.setText(EpgGrid.minutesLeft(p, n) + " min left");
        } else {
            progress.setVisibility(View.GONE);
            infoLeft.setText(p.start > n ? "   Starts in " + duration(p.start - n) : "");
        }
        infoDesc.setText(p.description);
        EpgGrid.Program nx = progs == null ? null : EpgGrid.next(progs, p);
        infoNext.setText(nx == null ? "" : "Next at " + when(nx.start) + " · " + nx.title);
    }

    private String fmt(long t) { return timeFmt.format(new java.util.Date(t)); }

    private String when(long t) {
        return DateUtils.isToday(t) ? fmt(t) : DateUtils.formatDateTime(a, t, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY) + " " + fmt(t);
    }

    private static String duration(long ms) {
        long min = (ms + EpgGrid.MINUTE - 1) / EpgGrid.MINUTE;
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    // ---------------------------------------------------------------- guide data

    private String streamId(int row) {
        JSONObject ch = channels.optJSONObject(row);
        return ch == null ? "" : ch.optString("stream_id");
    }

    /** The small second line under a channel's name: its category, or (in a list mixing playlists) its playlist. */
    private String subLabel(int row) {
        JSONObject ch = channels.optJSONObject(row);
        if (ch == null) return "";
        if (otherPlaylist(row) == null) {
            String cid = ch.optString("category_id");
            for (int k = 0; !cid.isEmpty() && k < a.categories.length(); k++) {
                JSONObject c = a.categories.optJSONObject(k);
                if (c != null && cid.equals(c.optString("category_id"))) return c.optString("category_name");
            }
        } else {
            String other = GuideSources.categoryName(ch.optString("playlist"), ch.optString("category_id"));
            if (!other.isEmpty()) return other;
        }
        if (!mixed) return categoryTitle;
        JSONObject p = otherPlaylist(row);
        return Library.nameOf(p != null ? p : a.library.find(a.library.activeId()));
    }

    /** Hold OK on a programme: Favorites, Hide / Unhide or Add to channel group for this channel. */
    private void options(int row) {
        JSONObject ch = channels.optJSONObject(row);
        final int s = session;
        GuideOptions.show(a, ch, (hidden, fav) -> {
            if (s != session) return;
            // Taken off Favorites while showing Favorites: the row leaves. A hidden row stays, dimmed, so it can be switched back.
            if (!fav && "fav".equals(a.category)) {
                if (row < channels.length() && channels.optJSONObject(row) == ch) channels.remove(row);
                focusRow = Math.max(0, Math.min(row, channels.length() - 1));
                page = focusRow / PAGE;
            }
            buildRows();
        });
    }

    /** The channel's playlist when it isn't the active one, else null. */
    private JSONObject otherPlaylist(int row) {
        JSONObject ch = channels.optJSONObject(row);
        String pid = ch == null ? "" : ch.optString("playlist");
        return pid.isEmpty() || pid.equals(a.library.activeId()) ? null : a.library.find(pid);
    }

    /** Where this channel's favorite is saved: its own playlist's prefix. */
    private String prefixOf(int row) {
        JSONObject p = otherPlaylist(row);
        return p == null ? a.accountPrefix() : Library.prefixOf(p);
    }

    /** Playlist + channel id, since two providers can use the same channel number. */
    private String key(int row) {
        JSONObject ch = channels.optJSONObject(row);
        String pid = ch == null ? "" : ch.optString("playlist");
        return (pid.isEmpty() ? a.library.activeId() : pid) + "|" + streamId(row);
    }

    private void requestEpg(int row) {
        String sid = streamId(row), id = key(row);
        if (sid.isEmpty() || pool == null || epg.containsKey(id) || !requested.add(id)) return;
        final int s = session;
        final JSONObject other = otherPlaylist(row);
        final String action = "get_short_epg&stream_id=" + a.enc(sid) + "&limit=12";
        pool.execute(() -> {
            List<EpgGrid.Program> progs = new ArrayList<>();
            try {
                // Provider guide, the playlist's XMLTV guide, or the guide link set in Settings.
                {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    JSONArray list = (other == null ? a.apiBlocking(action) : a.library.epgFor(other, action)).optJSONArray("epg_listings");
                    TimeZone tz = TimeZone.getDefault();
                    if (list != null) for (int k = 0; k < list.length(); k++) {
                        JSONObject e = list.optJSONObject(k);
                        if (e == null) continue;
                        long start = EpgGrid.parseTime(e.optString("start_timestamp"), e.optString("start"), tz);
                        long end = EpgGrid.parseTime(e.optString("stop_timestamp"), e.optString("end", e.optString("stop")), tz);
                        progs.add(new EpgGrid.Program(start, end, a.epgText(e.optString("title")), a.epgText(e.optString("description"))));
                    }
                }
            } catch (InterruptedException | java.io.InterruptedIOException stopped) {
                ui.post(() -> requested.remove(id));
                return;
            } catch (Exception ignored) {
                // No guide for this channel; the row says so and the channel is still watchable.
            }
            List<EpgGrid.Program> clean = EpgGrid.clean(progs);
            ui.post(() -> {
                epg.put(id, clean);
                requested.remove(id);
                if (s != session) return;
                for (int k = 0; k < rowCells.size(); k++) if (key(rowCells.keyAt(k)).equals(id)) fillRow(rowCells.keyAt(k));
            });
        });
    }

    // ---------------------------------------------------------------- preview and playback

    private String streamUrl(int row) {
        JSONObject ch = channels.optJSONObject(row);
        String ext = ch == null ? "ts" : ch.optString("container_extension", "ts");
        if (ext.isEmpty()) ext = "ts";
        JSONObject other = otherPlaylist(row);
        return other == null ? a.streamUrl("live", streamId(row), ext) : a.library.streamUrlFor(other, streamId(row), ext);
    }

    private void schedulePreview(int row) {
        if (pendingPreview != null) ui.removeCallbacks(pendingPreview);
        pendingPreview = null;
        syncPreviewLabel();
        // The first channel after the guide opens previews right away; the Settings delay (or OK only) is for later changes.
        int delay = preview == null ? 0 : PreviewDelay.ms(a.prefs);
        if (delay < 0) return; // "OK only": OK on a channel previews it
        final int s = session;
        pendingPreview = () -> { if (s == session && focusRow == row) startPreview(row); };
        ui.postDelayed(pendingPreview, delay);
    }

    /** The preview's caption names the channel actually playing (with a delay or "OK only" it can lag the highlight). */
    private void syncPreviewLabel() {
        if (previewLabel == null || PreviewDelay.DEFAULT.equals(PreviewDelay.key(a.prefs))) return;
        if (preview != null && previewName != null) previewLabel.setText(previewName + " · live preview");
        else previewLabel.setText(previewLabel.isInTouchMode() ? "Tap a channel name to preview it" : PreviewDelay.okOnly(a.prefs) ? "Press OK to preview" : "Rest on a channel to preview it");
    }

    private void startPreview(int row) {
        if (MiniPlayer.active()) return; // the mini player is playing: no second stream (or tuner) for the preview
        String id = key(row);
        if (streamId(row).isEmpty() || (preview != null && id.equals(previewId))) return;
        String url = streamUrl(row);
        if (url == null) {
            // A channel from another M3U playlist: read that playlist first, then preview.
            JSONObject other = otherPlaylist(row);
            final int s = session;
            if (other != null && pool != null) pool.execute(() -> {
                try { a.library.m3uOf(other); } catch (Exception e) { return; }
                ui.post(() -> { if (s == session && focusRow == row) startPreview(row); });
            });
            return;
        }
        previewId = id;
        JSONObject ch = channels.optJSONObject(row);
        previewName = ch == null ? "Channel" : ch.optString("name", "Channel");
        previewError.setVisibility(View.GONE);
        if (preview == null) {
            preview = Players.create(a);
            previewView.setPlayer(preview);
            preview.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    if (Players.useFfmpegAudio(preview, error)) { preview.prepare(); return; }
                    previewError.setVisibility(View.VISIBLE);
                }
            });
        }
        preview.setMediaItem(MediaItem.fromUri(url));
        preview.prepare();
        preview.play();
        syncPreviewLabel();
    }

    private void releasePreview() {
        if (pendingPreview != null) ui.removeCallbacks(pendingPreview);
        if (preview != null) {
            if (previewView != null) previewView.setPlayer(null);
            preview.release();
            preview = null;
        }
        previewId = null;
        previewName = null;
    }

    private void playFullScreen(int row) {
        JSONObject ch = channels.optJSONObject(row);
        if (ch == null) return;
        focusRow = row;
        releasePreview();
        // A channel from another playlist: switch to it first (the guide keeps its list), and zap only within that playlist.
        String pid = ch.optString("playlist");
        JSONArray zap = channels;
        if (mixed) {
            String want = pid.isEmpty() ? a.library.activeId() : pid;
            zap = new JSONArray();
            for (int k = 0; k < channels.length(); k++) { JSONObject o = channels.optJSONObject(k); if (o != null && (o.optString("playlist").isEmpty() ? a.library.activeId() : o.optString("playlist")).equals(want)) zap.put(o); }
        }
        if (otherPlaylist(row) != null) {
            if (!VirtualCategories.useItsPlaylist(a, ch)) return;
            a.tvGuide = this;
        }
        a.playerReturn = this::show;
        a.zapList = zap; // CH+/CH- in the player follow the guide's channel order
        String ext = ch.optString("container_extension", "ts");
        a.play("live", streamId(row), ext.isEmpty() ? "ts" : ext, ch.optString("name", "Channel"));
    }

    /** Start on this channel the next time the guide is shown (used after zapping in the player). */
    void focusStream(String id) {
        for (int i = 0; i < channels.length(); i++) {
            if (id != null && id.equals(streamId(i))) { focusRow = i; page = i / PAGE; return; }
        }
    }

    /** Activity went to the background: stop the preview so it doesn't keep playing or hold the connection. */
    void pausePreview() { releasePreview(); }

    void resumePreview() { if (shownRoot != null && shownRoot.isAttachedToWindow()) schedulePreview(focusRow); }

    private void hide() {
        session++;
        shownRoot = null;
        ui.removeCallbacksAndMessages(null);
        if (pool != null) { pool.shutdownNow(); pool = null; }
        requested.clear();
        releasePreview();
    }
}
