package com.wygtv.tv;

import android.view.KeyEvent;

/** Plain remote-control rules with no Android runtime dependencies, so they can be unit tested. */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Longest channel number the remote's digit keys will collect. */
    static final int MAX_DIGITS = 4;

    /** The digit a key stands for (top-row or numpad), or -1 when it is not a digit key. */
    static int digit(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return keyCode - KeyEvent.KEYCODE_0;
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    /** Adds a typed digit to the channel number being entered. Leading zeros are dropped and long input keeps the newest digits. */
    static String appendDigit(String typed, int digit) {
        String next = (typed == null ? "" : typed) + digit;
        next = next.replaceFirst("^0+(?=\\d)", "");
        return next.length() > MAX_DIGITS ? next.substring(next.length() - MAX_DIGITS) : next;
    }

    /** Next position when zapping through a list, wrapping around both ends. */
    static int wrap(int index, int delta, int size) {
        if (size <= 0) return -1;
        return Math.floorMod(index + delta, size);
    }

    /** Position of the channel whose provider number matches, or -1. */
    static int indexOfNumber(String[] numbers, String wanted) {
        if (numbers == null || wanted == null || wanted.isEmpty()) return -1;
        for (int i = 0; i < numbers.length; i++) if (wanted.equals(numbers[i])) return i;
        return -1;
    }

    /** Focus jump used by CH+/CH- and Page keys on long lists. */
    static int pageJump(int index, int delta, int size) {
        if (size <= 0) return -1;
        return Math.max(0, Math.min(size - 1, index + delta));
    }

    /** The next item that can take focus from {@code at}, stepping by {@code step} (1 down, -1 up), or -1 at the end of the list. */
    static int nextFocusable(boolean[] focusable, int at, int step) {
        if (focusable == null) return -1;
        for (int i = at + step; i >= 0 && i < focusable.length; i += step) if (focusable[i]) return i;
        return -1;
    }

    /** Where focus wraps to from the end of a list: the first item going down, the last item going up, or -1 if none. */
    static int wrapTarget(boolean[] focusable, boolean down) {
        if (focusable == null) return -1;
        return down ? nextFocusable(focusable, -1, 1) : nextFocusable(focusable, focusable.length, -1);
    }
}
