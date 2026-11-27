package com.wygtv.tv;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The brass menu down the left of every screen. It shows icons only until focus moves onto it
 * (press Left from the left-most item), then slides open over the screen with labels.
 */
final class NavRail {
    static final int COLLAPSED_DP = 68, EXPANDED_DP = 250;
    /**
     * Tag a screen's top-right control with this, and Right from the top third of the menu goes straight
     * to it (sideways across the top of the screen) instead of back to where focus was further down.
     */
    static final String TOP_TARGET = "navrail_top_target";

    private final MainActivity a;
    private final LinearLayout rail;
    private final List<TextView> items = new ArrayList<>();
    private final List<String[]> labels = new ArrayList<>();
    private boolean expanded;
    private View lastContentFocus;
    private final List<String> keys = new ArrayList<>();
    private String current;
    /** The rail on the screen now showing, so Back can open it (see {@link #open}). */
    private static java.lang.ref.WeakReference<NavRail> showing = new java.lang.ref.WeakReference<>(null);

    /**
     * Opens the main menu on the left with this screen's entry highlighted. Back out of a screen's own menus lands
     * here instead of on Home; Home opens only when it's picked from this menu. False when there's no menu showing.
     */
    static boolean open(MainActivity a) {
        NavRail r = showing.get();
        if (r == null || !r.rail.isAttachedToWindow() || !r.rail.isShown() || r.items.isEmpty()) return false;
        if (r.rail.hasFocus()) return true;
        View f = a.getCurrentFocus();
        if (f != null && !r.inRail(f)) r.lastContentFocus = f;
        TextView target = r.hereItem();
        return (target != null ? target : r.items.get(0)).requestFocus();
    }

    /**
     * Back on a top screen (Home, or the TV Guide in Live TV only mode): the first press moves the highlight into the
     * side menu; Back again from the side menu is what asks Quit WygTV?. True when the menu was opened by this press.
     */
    static boolean openFromScreen(MainActivity a) {
        NavRail r = showing.get();
        if (r == null || !r.rail.isAttachedToWindow() || !r.rail.isShown() || r.rail.hasFocus()) return false;
        return open(a);
    }

    /** Wraps {@code content} in a frame with the rail on its left and returns the frame to show. */
    static FrameLayout wrap(MainActivity a, View content, String current) {
        FrameLayout frame = new FrameLayout(a);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.leftMargin = a.dp(COLLAPSED_DP);
        frame.addView(content, lp);
        // Added after the content so the content's first item gets focus by default.
        NavRail r = new NavRail(a, current);
        r.current = current;
        showing = new java.lang.ref.WeakReference<>(r);
        frame.addView(r.rail, new FrameLayout.LayoutParams(a.dp(COLLAPSED_DP), -1, Gravity.START));
        // Remember where focus was in the screen, so Right (or OK on the current screen) goes back there.
        // Registered on the window while this screen is showing, and removed when it goes, so old screens don't pile up.
        android.view.ViewTreeObserver.OnGlobalFocusChangeListener track = (oldFocus, newFocus) -> {
            if (oldFocus != null && newFocus != null && r.inRail(newFocus) && !r.inRail(oldFocus)) {
                r.lastContentFocus = oldFocus;
                // Coming in from the screen (◀): land on the entry for this screen, the one picked to get here,
                // not whichever entry happens to be level with where the focus was.
                TextView here = r.hereItem();
                if (here != null && newFocus != here) r.rail.post(here::requestFocus);
            }
        };
        frame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            android.view.ViewTreeObserver observer;
            @Override public void onViewAttachedToWindow(View v) { observer = v.getViewTreeObserver(); observer.addOnGlobalFocusChangeListener(track); }
            @Override public void onViewDetachedFromWindow(View v) { if (observer != null && observer.isAlive()) observer.removeOnGlobalFocusChangeListener(track); observer = null; }
        });
        return frame;
    }

    private NavRail(MainActivity a, String current) {
        this.a = a;
        rail = new LinearLayout(a);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setTag(FocusWrap.MENU); // Up/Down stay in the menu (FocusWrap.menuKey), wrapping at the ends
        rail.setPadding(a.dp(8), a.dp(16), a.dp(8), a.dp(12));
        rail.setBackground(background(false));
        boolean vod = a.library.hasVod(); // any switched-on playlist with movies and series
        // Each movable entry under its Settings → Appearance → Side menu order key; laid out in the saved order.
        java.util.Map<String, Runnable> entries = new java.util.HashMap<>();
        if (a.liveOnly()) {
            // Live TV only (Settings → App mode): the guide is the main screen, plus Live TV and Settings.
            entries.put("guide", () -> add("▦", "TV Guide", "guide", current, () -> a.openGuide(null)));
            entries.put("live", () -> add("▣", "Live TV", "live", current, () -> a.browse("live")));
            entries.put("msearch", () -> add("⌕", "Search channels", "search", current, () -> a.searchLive()));
            entries.put("settings", () -> add("⚙", "Settings", "settings", current, () -> a.settings()));
        } else {
            entries.put("home", () -> add("⌂", "Home", "home", current, () -> a.home()));
            entries.put("guide", () -> add("▦", "TV Guide", "guide", current, () -> a.openGuide(null)));
            entries.put("live", () -> add("▣", "Live TV", "live", current, () -> a.browse("live")));
            if (vod) {
                entries.put("vod", () -> add("▶", "Movies", "vod", current, () -> a.browseVod("vod")));
                entries.put("series", () -> add("▤", "Series", "series", current, () -> a.browseVod("series")));
            }
            java.util.List<org.json.JSONObject> tuners = Antenna.tuners(a);
            boolean airTv = AirTv.isOn(a.prefs), tabloApp = TabloApp.isOn(a.prefs);
            if (!tuners.isEmpty() || airTv || tabloApp) entries.put("antenna", () -> {
                for (org.json.JSONObject t : tuners) { // each HDHomeRun tuner
                    String id = Antenna.PREFIX + t.optString("id");
                    add("📡", Antenna.nameOf(t), id, current, () -> a.showGuide(id, null, true)); // full TV guide of its channels
                }
                if (airTv) add("📡", AirTv.nameOf(a.prefs), "airtv", current, () -> AirTv.open(a)); // opens the Sling app
                if (tabloApp) add("📡", TabloApp.nameOf(a.prefs), "tabloapp", current, () -> TabloApp.open(a)); // opens the Tablo app (4th Gen)
            });
            entries.put("msearch", () -> add("⌕", "Search", "msearch", current, () -> MasterSearch.show(a)));
            entries.put("playlists", () -> add("☰", "Playlists", "playlists", current, () -> { Menus.playlistsFromSettings = false; a.playlists(); }));
            entries.put("settings", () -> add("⚙", "Settings", "settings", current, () -> a.settings()));
        }
        java.util.List<String> keys = new ArrayList<>();
        for (String k : MenuOrder.get(a.prefs)) if (entries.containsKey(k)) keys.add(k);
        int gap = MenuOrder.gapBefore(keys);
        for (int i = 0; i < keys.size(); i++) {
            if (i == gap) rail.addView(new View(a), new LinearLayout.LayoutParams(-1, 0, 1));
            entries.get(keys.get(i)).run();
        }
        if (gap >= keys.size()) rail.addView(new View(a), new LinearLayout.LayoutParams(-1, 0, 1));
        // Small Exit at the very bottom: asks "Quit WygTV?" first.
        add("⏻", "Exit", "exit", current, () -> a.confirmExit());
        TextView exit = items.get(items.size() - 1);
        exit.setTextSize(16);
        exit.getLayoutParams().height = a.dp(34);
        exit.setTextColor(Theme.MUTED);
        fitHeight();
        lockEnds();
    }

    /** With many entries (several tuners), shrink the rows so every one fits on screen and none hides below the edge. */
    private void fitHeight() {
        int screen = a.getResources().getDisplayMetrics().heightPixels;
        int room = screen - a.dp(16 + 12) - items.size() * a.dp(4);
        int want = 0;
        for (TextView v : items) want += v.getLayoutParams().height;
        if (want <= room) return;
        float k = Math.max(0.6f, room / (float) want);
        for (TextView v : items) {
            v.getLayoutParams().height = Math.round(v.getLayoutParams().height * k);
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, v.getTextSize() * Math.max(0.75f, k));
        }
    }

    /**
     * Up and Down move only between menu items (Up from the top goes to the bottom, Down from the bottom back to
     * the top), so scrolling the menu never jumps into the screen behind it. Left stays put.
     */
    private void lockEnds() {
        int n = items.size();
        for (int i = 0; i < n; i++) items.get(i).setId(View.generateViewId());
        for (int i = 0; i < n; i++) {
            TextView v = items.get(i);
            v.setNextFocusUpId(items.get((i - 1 + n) % n).getId());
            v.setNextFocusDownId(items.get((i + 1) % n).getId());
            v.setNextFocusLeftId(v.getId());
        }
    }

    /** The menu item {@code step} places from {@code v}, wrapping around the ends. */
    private TextView neighbour(View v, int step) {
        int n = items.size(), i = items.indexOf(v);
        return i < 0 || n == 0 ? null : items.get(((i + step) % n + n) % n);
    }

    private void add(String icon, String label, String key, String current, Runnable action) {
        boolean here = key.equals(current);
        TextView v = new TextView(a);
        v.setText(icon);
        v.setTextSize(22);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        v.setPadding(a.dp(14), 0, a.dp(8), 0);
        v.setTextColor(here ? Theme.ACCENT : Theme.TEXT);
        if (here) v.setTypeface(null, Typeface.BOLD);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(itemBg(false, here));
        v.setContentDescription(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(44));
        lp.setMargins(0, a.dp(2), 0, a.dp(2));
        rail.addView(v, lp);
        v.setOnClickListener(w -> { if (!here || key.equals("home")) action.run(); else collapseAndReturn(); });
        v.setOnFocusChangeListener((w, focused) -> {
            v.setBackground(itemBg(focused, here));
            v.setTextColor(focused ? Theme.INK : here ? Theme.ACCENT : Theme.TEXT);
            if (focused) setExpanded(true);
            else rail.post(() -> { if (!rail.hasFocus()) setExpanded(false); });
        });
        // Right closes the menu and goes back into the screen.
        v.setOnKeyListener((w, code, e) -> {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { if (!acrossTop(w)) collapseAndReturn(); return true; }
            // Up/Down stay in the menu; Left does nothing.
            if (code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    TextView next = neighbour(w, code == android.view.KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    if (next != null) next.requestFocus();
                }
                return true;
            }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) return true;
            // Back closes the menu first instead of leaving the screen.
            if (code == android.view.KeyEvent.KEYCODE_BACK) { if (e.getAction() == android.view.KeyEvent.ACTION_UP) collapseAndReturn(); return true; }
            return false;
        });
        items.add(v);
        keys.add(key);
        labels.add(new String[]{icon, label});
    }

    /**
     * Arrow keys never jump from a screen into the main side menu: at the edge of a screen the highlight stays where
     * it is (Back opens the side menu). True when this arrow press would have moved into the side menu, so it is
     * used up instead. Touch is unaffected.
     */
    static boolean blocksArrow(MainActivity a, android.view.KeyEvent e) {
        int k = e.getKeyCode();
        if (k != android.view.KeyEvent.KEYCODE_DPAD_LEFT && k != android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                && k != android.view.KeyEvent.KEYCODE_DPAD_UP && k != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false;
        NavRail r = showing.get();
        if (r == null || !r.rail.isAttachedToWindow() || !r.rail.isShown()) return false;
        View f = a.getCurrentFocus();
        if (f == null || r.inRail(f)) return false;
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
        int dir = k == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                : k == android.view.KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN;
        View root = f.getRootView();
        if (!(root instanceof android.view.ViewGroup)) return false;
        View next = android.view.FocusFinder.getInstance().findNextFocus((android.view.ViewGroup) root, f, dir);
        return next != null && r.inRail(next);
    }

    /** This screen's entry in the menu (e.g. ▣ Live TV on the Live TV screen), or null when it has none. */
    private TextView hereItem() {
        for (int i = 0; i < items.size() && i < keys.size(); i++) if (keys.get(i).equals(current)) return items.get(i);
        return null;
    }

    private boolean inRail(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == rail) return true;
        return v == rail;
    }

    private void collapseAndReturn() {
        ViewGroup frame = (ViewGroup) rail.getParent();
        View content = frame == null ? null : frame.getChildAt(0);
        setExpanded(false);
        if (content instanceof ViewGroup) {
            View target = lastContentFocus != null && lastContentFocus.isAttachedToWindow() && lastContentFocus.isShown() ? lastContentFocus : null;
            if (target == null) target = firstFocusable((ViewGroup) content);
            if (target != null) target.requestFocus();
        }
    }

    /** Right from an item in the top third of the menu: jump to the screen's top-right control, if it has one. */
    private boolean acrossTop(View item) {
        ViewGroup frame = (ViewGroup) rail.getParent();
        View content = frame == null ? null : frame.getChildAt(0);
        if (content == null || rail.getHeight() == 0) return false;
        if (item.getTop() + item.getHeight() / 2 > rail.getHeight() / 3) return false;
        View target = content.findViewWithTag(TOP_TARGET);
        if (target == null || !target.isShown() || !target.isFocusable()) return false;
        setExpanded(false);
        return target.requestFocus();
    }

    private static View firstFocusable(ViewGroup g) {
        java.util.ArrayList<View> out = new java.util.ArrayList<>();
        g.addFocusables(out, View.FOCUS_FORWARD);
        return out.isEmpty() ? null : out.get(0);
    }

    private void setExpanded(boolean open) {
        if (open == expanded) return;
        expanded = open;
        ViewGroup.LayoutParams lp = rail.getLayoutParams();
        lp.width = a.dp(open ? EXPANDED_DP : COLLAPSED_DP);
        rail.setLayoutParams(lp);
        rail.setBackground(background(open));
        for (int i = 0; i < items.size(); i++) items.get(i).setText(open ? labels.get(i)[0] + "   " + labels.get(i)[1] : labels.get(i)[0]);
    }

    private GradientDrawable background(boolean open) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                open ? new int[]{Theme.pane(Theme.alpha(Theme.BG,0xfa)), Theme.pane(Theme.alpha(Theme.PANEL,0xf2))} : new int[]{Theme.pane(Theme.alpha(Theme.BG,0xe6)), Theme.pane(Theme.alpha(Theme.PANEL,0xcc))});
        g.setStroke(a.dp(1), Theme.edge(false));
        return g;
    }

    private GradientDrawable itemBg(boolean focused, boolean here) {
        if (focused) { GradientDrawable t = Skin.tile(a, true); t.setCornerRadius(a.dp(10)); t.setStroke(a.dp(2), Theme.TILE_STROKE); return t; }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                here ? new int[]{Theme.alpha(Theme.FOCUS,0x70), Theme.alpha(Theme.FOCUS,0x40)} : new int[]{0x00000000, 0x00000000});
        g.setCornerRadius(a.dp(10));
        if (here) g.setStroke(a.dp(1), Theme.edge(false));
        return g;
    }
}
