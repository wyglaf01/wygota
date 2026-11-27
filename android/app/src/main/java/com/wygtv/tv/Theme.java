package com.wygtv.tv;

/**
 * Colour themes picked in Settings (Royal blue & bronze unless changed). Every theme follows the royal blue & bronze style:
 * a deep main colour for panels and focus, with bronze (or gold) trim, headings and focused tiles. Themes keep the
 * original brass-on-leather artwork; the others wash the artwork in their own colour.
 * Settings > Appearance > Glass effect turns panels and buttons into frosted see-through glass (see {@link #glass}).
 */
final class Theme {
    static final String[] KEYS = {"royal", "cyan", "green", "brown", "gold"};
    static final String[] LABELS = {"Royal blue & bronze (default)", "Teal & bronze", "Emerald & bronze", "Mahogany & bronze", "Black & gold"};

    // The shared trim: Order STROKE, STROKE_FOCUS, ACCENT, HEADER, INK, TILE_A, TILE_B, TILE_STROKE, COPPER
    private static final int[] BRONZE = {0xffA8743A, 0xffF0C080, 0xffE6B070, 0xffD9A060, 0xff1A0F04, 0xffF0C88A, 0xffB0743A, 0xffFFE6BC, 0xffCD7F32};
    private static final int[] GOLD = {0xffB8913A, 0xffF5D488, 0xffEDC66E, 0xffE0B655, 0xff1A1404, 0xffF5D58E, 0xffB8893A, 0xffFFF0C4, 0xffD9A632};
    private static final int[] BRASS = {0xff8A6A3A, 0xffF0C878, 0xffEACB86, 0xffE8B86A, 0xff2A1708, 0xffF0D797, 0xffBA904A, 0xffFFF0C1, 0xffD9822B};

    // Each theme's own colours. Order: BG, PANEL, PANEL_END, FOCUS, TEXT, MUTED, HINT, AIRING
    private static final int[][] BASES = {
            {0xff060C24, 0xff1A3480, 0xe60A1640, 0xff2D52C4, 0xffF4EFE6, 0xffC6CADD, 0xff8C93B8, 0xff23408F}, // royal blue
            {0xff04161A, 0xff0E4A52, 0xe6062428, 0xff168A96, 0xffEEF8F6, 0xffB6D3D2, 0xff7FA6A6, 0xff13606A}, // teal
            {0xff04140C, 0xff0F4A2C, 0xe6062614, 0xff1E7A48, 0xffEEF8F0, 0xffB8D6C2, 0xff82A68E, 0xff145C36}, // emerald
            {0xff160A08, 0xff5A2418, 0xe62A100A, 0xff8E3A26, 0xffF6ECE2, 0xffD9C2B2, 0xffA88E7E, 0xff6E2E1E}, // mahogany
            {0xff0A0A0C, 0xff2A2A31, 0xe6141418, 0xff4C4C58, 0xffF5F1E8, 0xffCFCAC0, 0xff9A968E, 0xff3A3A44}, // black
    };
    private static final int[][] TRIMS = {BRONZE, BRONZE, BRONZE, BRONZE, GOLD};

    /** Used when no theme has been picked in Settings. */
    static final String DEFAULT = "royal";
    static String key = DEFAULT;
    static int BG, PANEL, PANEL_END, STROKE, STROKE_FOCUS, FOCUS, ACCENT, HEADER, TEXT, MUTED, HINT, INK, TILE_A, TILE_B, TILE_STROKE, COPPER, AIRING;

    static { apply(DEFAULT); }

    private Theme() { }

    static void apply(String k) {
        int i = indexOf(k);
        key = KEYS[i];
        int[] b = BASES[i], t = TRIMS[i];
        BG = b[0]; PANEL = b[1]; PANEL_END = b[2]; FOCUS = b[3]; TEXT = b[4]; MUTED = b[5]; HINT = b[6]; AIRING = b[7];
        STROKE = t[0]; STROKE_FOCUS = t[1]; ACCENT = t[2]; HEADER = t[3]; INK = t[4]; TILE_A = t[5]; TILE_B = t[6]; TILE_STROKE = t[7]; COPPER = t[8];
    }

    static int indexOf(String k) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) return i;
        return k.equals(DEFAULT) ? 0 : indexOf(DEFAULT);
    }

    /** Themes that were removed, and the theme their users move to. */
    static String migrate(String k) {
        return k == null || k.equals("blue") ? DEFAULT : k;
    }

    /** Settings > Appearance > Glass effect: frosted see-through panels and buttons over a blurred backdrop. */
    static final String PREF_GLASS = "glass_effect";
    static boolean glass;

    /** Top and bottom fill of a button, row or panel: solid, or see-through glass with a light sheen on top. */
    static int[] surface(boolean focused) {
        if (glass) return focused
                ? new int[]{alpha(mix(FOCUS, 0xffffffff, 0.18f), 0xb8), alpha(FOCUS, 0x8c)}
                : new int[]{alpha(mix(PANEL, 0xffffffff, 0.14f), 0x5c), alpha(mix(PANEL, BG, 0.5f), 0x3d)};
        return focused
                ? new int[]{mix(FOCUS, 0xffffffff, 0.10f), FOCUS}
                : new int[]{alpha(PANEL, 0xf2), alpha(mix(PANEL, BG, 0.55f), 0xf2)};
    }

    /** The thin edge around a button, row or panel. Glass gets a frosted light edge, solid gets bronze. */
    static int edge(boolean focused) {
        if (focused) return STROKE_FOCUS;
        return glass ? alpha(mix(STROKE_FOCUS, 0xffffffff, 0.45f), 0x73) : alpha(STROKE, 0xb3);
    }

    /** A panel colour with the glass see-through applied (alpha scaled down when the glass effect is on). */
    static int pane(int color) {
        if (!glass) return color;
        int a = color >>> 24;
        return alpha(color, Math.round(a * 0.55f));
    }

    static boolean steampunk() { return false; }

    /** The colour with its alpha replaced. */
    static int alpha(int color, int a) { return (color & 0x00ffffff) | (a << 24); }

    /** A mix of two colours, t = 0 gives a, t = 1 gives b (alpha mixed too). */
    static int mix(int a, int b, float t) {
        int r = 0;
        for (int s = 0; s <= 24; s += 8) {
            int ca = (a >>> s) & 0xff, cb = (b >>> s) & 0xff;
            r |= (Math.round(ca + (cb - ca) * t) & 0xff) << s;
        }
        return r;
    }
}
