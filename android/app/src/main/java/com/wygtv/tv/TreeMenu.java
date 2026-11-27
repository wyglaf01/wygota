package com.wygtv.tv;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Cascading menu tree for the pages under Settings (and anywhere else that opens a page from a list): the level
 * you came from stays on the far left as a narrow column of short one-line entries, with the one you are in marked,
 * while the page you picked fills the rest of the screen. Left from the page goes into that column (onto the marked
 * entry), Right goes back to where you were on the page, and OK on any entry in the column jumps to it. It is the same
 * look as the Live TV menu's compacted first tier.
 */
final class TreeMenu {
    private static ScrollView scroll;
    private static LinearLayout column;
    private static TextView current;
    private static View main;
    private static View lastMain;

    private TreeMenu() { }

    /**
     * Starts the narrow parent column on the left of {@code a.body} (call right after {@code shell()}, before the page
     * itself is added). Add its entries with {@link #entry}, then call {@link #page} with the page's own scroll view.
     */
    static void begin(MainActivity a) {
        column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, a.dp(8));
        scroll = new ScrollView(a);
        scroll.setTag(FocusWrap.MENU); // Up/Down stay in it, wrapping at the ends
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(column);
        a.body.addView(scroll, new LinearLayout.LayoutParams(a.dp(LiveMenu.NARROW_DP), -1));
        current = null;
        main = null;
        lastMain = null;
    }

    /** A small heading in the column (the name of the level the entries below belong to). */
    static void heading(MainActivity a, String text) {
        TextView h = a.label(text, 13);
        h.setTextColor(Theme.ACCENT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        h.setPadding(a.dp(10), a.dp(6), a.dp(6), a.dp(2));
        column.addView(h, new LinearLayout.LayoutParams(-1, -2));
    }

    /** One short entry in the column. {@code here} marks the entry whose page is open. */
    static TextView entry(MainActivity a, String icon, String name, boolean here, Runnable ok) {
        TextView v = a.button(icon + "  " + name, column, ok);
        v.setTextSize(16);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(a.dp(10), a.dp(9), a.dp(6), a.dp(9));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.setMargins(a.dp(4), a.dp(3), a.dp(4), a.dp(3));
        v.setLayoutParams(lp);
        if (here) {
            v.setTextColor(Theme.ACCENT);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            current = v;
        } else v.setTextColor(Theme.MUTED);
        View.OnFocusChangeListener old = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener((w, f) -> {
            if (old != null) old.onFocusChange(w, f);
            if (!here) v.setTextColor(f ? 0xffffffff : Theme.MUTED);
        });
        return v;
    }

    /** The page beside the column (its scroll view). Scrolls the column so the marked entry shows. */
    static void page(View pageView) {
        main = pageView;
        if (current != null && scroll != null) {
            TextView cur = current;
            ScrollView s = scroll;
            LinearLayout col = column;
            s.post(() -> s.requestChildRectangleOnScreen(col, new android.graphics.Rect(0, cur.getTop(), cur.getWidth(), cur.getBottom()), true));
        }
    }

    /**
     * Left from the page into the column, Right from the column back into the page. Called from TvRemote before
     * other keys. Returns true when the key was used.
     */
    static boolean sideways(MainActivity a, KeyEvent e) {
        int key = e.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (scroll == null || main == null || !scroll.isAttachedToWindow() || !scroll.isShown()) return false;
        View f = a.getCurrentFocus();
        if (f == null) return false;
        boolean left = key == KeyEvent.KEYCODE_DPAD_LEFT;
        if (left && inside(f, main)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                lastMain = f;
                if (current != null) current.requestFocus();
                else focusFirst(column);
            }
            return true;
        }
        if (!left && inside(f, scroll)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (lastMain != null && lastMain.isAttachedToWindow() && lastMain.isShown() && inside(lastMain, main)) lastMain.requestFocus();
                else if (main instanceof android.view.ViewGroup) focusFirst((android.view.ViewGroup) main);
            }
            return true;
        }
        return false;
    }

    private static void focusFirst(android.view.ViewGroup g) {
        java.util.ArrayList<View> all = new java.util.ArrayList<>();
        g.addFocusables(all, View.FOCUS_DOWN);
        for (View v : all) if (v != g && v.isShown()) { v.requestFocus(); return; }
    }

    private static boolean inside(View v, View group) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == group) return true;
        return v == group;
    }
}
