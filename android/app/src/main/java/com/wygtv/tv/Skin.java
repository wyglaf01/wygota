package com.wygtv.tv;

import android.graphics.drawable.GradientDrawable;

/**
 * The shapes behind buttons, rows, tiles and panels, in the current Theme. One place for corner sizes, edges and the
 * Glass effect, so every screen looks the same.
 */
final class Skin {
    private Skin() { }

    /** A button or list row: a soft top-to-bottom fill with a thin edge; focused rows get the theme colour and a bronze edge. */
    static GradientDrawable button(MainActivity a, boolean focused) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Theme.surface(focused));
        g.setCornerRadius(a.dp(12));
        g.setStroke(a.dp(focused ? 2 : 1), Theme.edge(focused));
        return g;
    }

    /** A home tile (and a focused guide cell): glass or solid when idle, polished bronze when focused. */
    static GradientDrawable tile(MainActivity a, boolean focused) {
        GradientDrawable g;
        if (focused) {
            int alpha = Theme.glass ? 0xec : 0xff;
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Theme.alpha(Theme.TILE_A, alpha), Theme.alpha(Theme.TILE_B, alpha)});
        } else {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Theme.surface(false));
        }
        g.setCornerRadius(a.dp(14));
        g.setStroke(a.dp(focused ? 3 : 1), focused ? Theme.TILE_STROKE : Theme.edge(false));
        return g;
    }

    /** A large panel (pop-ups, info boxes, the player banner). */
    static GradientDrawable panel(float dp, int radiusDp) {
        int[] fill = Theme.glass
                ? new int[]{Theme.alpha(Theme.mix(Theme.PANEL, 0xffffffff, 0.10f), 0xc8), Theme.alpha(Theme.BG, 0xd8)}
                : new int[]{Theme.PANEL, Theme.mix(Theme.PANEL, Theme.BG, 0.7f)};
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fill);
        g.setCornerRadius(radiusDp * dp);
        g.setStroke(Math.round(Theme.glass ? dp : 2 * dp), Theme.glass ? Theme.edge(false) : Theme.STROKE);
        return g;
    }
}
