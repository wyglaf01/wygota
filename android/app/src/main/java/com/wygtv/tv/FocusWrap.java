package com.wygtv.tv;

import android.app.Dialog;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;

/**
 * Wrap-around scrolling for menus and lists: Down on the last item goes back to the first,
 * and Up on the first item goes to the last. Holding the key stops at the end instead of looping.
 */
final class FocusWrap {
    private FocusWrap() { }

    /**
     * Screens: a scrolling column of buttons (side menu of categories, channel lists, Settings, Favorites…).
     * Returns true when the key was used.
     */
    static boolean screenKey(View root, View focused, KeyEvent e) {
        int key = e.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_DOWN && key != KeyEvent.KEYCODE_DPAD_UP) return false;
        if (root == null || focused == null || !(focused.getParent() instanceof LinearLayout)) return false;
        LinearLayout list = (LinearLayout) focused.getParent();
        if (list.getOrientation() != LinearLayout.VERTICAL || !(list.getParent() instanceof ScrollView)) return false;
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
        int n = list.getChildCount(), at = list.indexOfChild(focused);
        boolean[] can = new boolean[n];
        for (int i = 0; i < n; i++) can[i] = takesFocus(list.getChildAt(i));
        if (RemoteKeys.nextFocusable(can, at, down ? 1 : -1) >= 0) return false; // not at the end yet
        // Something else lines up above or below (a search box, the buttons over the channel list, a row inside the list): go there as usual.
        if (root instanceof ViewGroup) {
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) root, focused, down ? View.FOCUS_DOWN : View.FOCUS_UP);
            if (next != null && (isInside(next, list) || overlapsSideways(next, focused))) return false;
        }
        int target = RemoteKeys.wrapTarget(can, down);
        if (target < 0 || target == at) return false; // a single item (like a long plot): let the screen scroll
        if (e.getRepeatCount() > 0) return true; // holding the key: stop at the end
        View v = list.getChildAt(target);
        v.requestFocus();
        ScrollView sv = (ScrollView) list.getParent();
        sv.post(() -> { if (down) sv.smoothScrollTo(0, 0); else sv.fullScroll(View.FOCUS_DOWN); });
        return true;
    }

    /** Tag on a menu column (the side menu, the Live TV / Movies / Series menu): Up and Down never leave it. */
    static final String MENU = "focuswrap_menu";

    /**
     * Up/Down inside a column tagged {@link #MENU}: go to the next item above or below in that column, wrapping at the
     * ends (holding the key stops at the end). Focus only leaves the column sideways. Returns true when the key was used.
     */
    static boolean menuKey(View focused, KeyEvent e) {
        int key = e.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_DOWN && key != KeyEvent.KEYCODE_DPAD_UP) return false;
        ViewGroup column = menuOf(focused);
        if (column == null) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        java.util.ArrayList<View> all = new java.util.ArrayList<>();
        column.addFocusables(all, View.FOCUS_DOWN);
        java.util.ArrayList<View> items = new java.util.ArrayList<>();
        for (View v : all) if (v != column && v.isShown()) items.add(v);
        if (items.isEmpty()) return true;
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
        int[] here = top(focused);
        View best = null; int bestY = 0, bestX = 0;
        for (View v : items) {
            if (v == focused) continue;
            int[] p = top(v);
            if (down ? p[1] <= here[1] : p[1] >= here[1]) continue;
            int dy = Math.abs(p[1] - here[1]), dx = Math.abs(p[0] - here[0]);
            if (best == null || dy < bestY || (dy == bestY && dx < bestX)) { best = v; bestY = dy; bestX = dx; }
        }
        if (best == null) { // at the end: wrap to the other end, unless the key is held down
            if (e.getRepeatCount() > 0) return true;
            for (View v : items) {
                if (v == focused) continue;
                int[] p = top(v);
                int y = down ? p[1] : -p[1], dx = Math.abs(p[0] - here[0]);
                if (best == null || y < bestY || (y == bestY && dx < bestX)) { best = v; bestY = y; bestX = dx; }
            }
        }
        if (best != null) best.requestFocus();
        return true;
    }

    private static ViewGroup menuOf(View v) {
        for (android.view.ViewParent p = v == null ? null : v.getParent(); p instanceof ViewGroup; p = p.getParent())
            if (MENU.equals(((ViewGroup) p).getTag())) return (ViewGroup) p;
        return null;
    }

    private static int[] top(View v) {
        int[] p = new int[2];
        v.getLocationOnScreen(p);
        return p;
    }

    private static boolean takesFocus(View v) {
        return v != null && v.isShown() && v.isFocusable();
    }

    private static boolean isInside(View v, View parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent())
            if (p == parent) return true;
        return false;
    }

    private static boolean overlapsSideways(View a, View b) {
        int[] pa = top(a), pb = top(b);
        return pa[0] < pb[0] + b.getWidth() && pb[0] < pa[0] + a.getWidth();
    }

    /** Dialog lists: wrap focus. */
    static boolean dialogKey(Dialog d, ListView list, KeyEvent e) {
        int key = e.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_DOWN && key != KeyEvent.KEYCODE_DPAD_UP) return false;
        if (list == null || list.getCount() == 0) return false;
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
        int pos = list.getSelectedItemPosition();
        if (down && pos < list.getCount() - 1) return false;
        if (!down && pos > 0) return false;
        if (e.getRepeatCount() > 0) return true;
        list.setSelection(down ? 0 : list.getCount() - 1);
        return true;
    }
}
