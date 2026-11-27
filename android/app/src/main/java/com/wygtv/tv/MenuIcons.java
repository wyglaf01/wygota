package com.wygtv.tv;

/** Short labels for a menu shrunk to a strip of icons (see LiveMenu). Plain Java, so it can be unit tested. */
final class MenuIcons {
    private MenuIcons() { }

    /** The icon a menu label starts with ("★ Favorites" → "★"), or "" when it starts with a word. */
    static String iconOf(String label) {
        String s = label.trim();
        if (s.isEmpty() || Character.isLetterOrDigit(s.codePointAt(0))) return "";
        int end = 0;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        return s.substring(0, end);
    }

    /**
     * What a menu entry shows in the icon strip: its icon, plus the first letter of its name when the icon is
     * shared with other entries; just that letter when it has no icon ("All categories" → "A").
     */
    static String iconLabel(String label, boolean shared) {
        String s = label.trim(), icon = iconOf(s);
        String name = s.substring(icon.length());
        String initial = "";
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) { initial = new String(Character.toChars(Character.toUpperCase(cp))); break; }
            i += Character.charCount(cp);
        }
        if (icon.isEmpty()) return initial.isEmpty() ? s : initial;
        return shared && !initial.isEmpty() ? icon + initial : icon;
    }
}
