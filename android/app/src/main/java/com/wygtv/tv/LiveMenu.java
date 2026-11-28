package com.wygtv.tv;

import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The Live TV menu on the left, in two tiers. The first tier has Favorites, Search, your own added categories and
 * your added playlists. Picking a playlist opens its provider categories as the second tier. Whatever is picked
 * shows in the mini TV guide on the right half.
 */
final class LiveMenu {
    static final String TOP = "top", CATEGORIES = "categories";
    /** Which tier is showing; kept while you play a channel so Back from the player returns to the same place. */
    static String tier = TOP;

    private LiveMenu() { }

    /** Called by browse("live") once the screen is built. */
    static void start(MainActivity a) {
        a.request("get_live_categories", o -> {
            a.categories = o.optJSONArray("list");
            if (a.categories == null) a.categories = new JSONArray();
            tier = TOP; // categories now open under their playlist in the one menu
            top(a);
        }, "Unable to load categories");
    }

    /** Menu entries made by {@link #pick} on the current first tier, by id. */
    private static final Map<String, android.widget.TextView> picks = new HashMap<>();
    /** Entry to highlight once the first tier is built (set by {@link #openAt}). */
    private static String focusOnOpen;

    /**
     * Opens Live TV at the first tier showing one entry ("fav" or an Antenna TV tuner). With focusIt the menu
     * highlights that entry; otherwise it starts on Favorites as usual.
     */
    static void openAt(MainActivity a, String id, boolean focusIt) {
        tier = TOP;
        a.resumeCategory = id;
        focusOnOpen = focusIt ? id : null;
        a.browse("live");
    }

    /** ← Menu: opens the main menu on the left (Home only opens when picked there). Live TV only mode goes to the guide. */
    private static void backOut(MainActivity a) {
        if (a.liveOnly() || !NavRail.open(a)) a.home();
    }

    /** First tier: Favorites, Search, added categories, added playlists. The full guide and antenna tuners are on the side rail (▦, 📡). */
    static void top(MainActivity a) {
        tier = TOP;
        lastHeader = null;
        hideParent();
        picks.clear();
        a.side.removeAllViews();
        a.button(a.liveOnly() ? "← TV Guide" : "← Menu", a.side, () -> backOut(a));
        pick(a, "★ Favorites", "fav");
        a.sideFixed = 1;
        a.button("⌕ Search", a.side, a::searchLive);
                if (!Brand.OTA && TabloApp.isOn(a.prefs)) a.button("📡 " + TabloApp.nameOf(a.prefs), a.side, () -> TabloApp.open(a)); // opens the Tablo app (4th Gen)
        JSONArray mine = VirtualCategories.all(a);
        for (int i = 0; i < mine.length(); i++) {
            JSONObject c = mine.optJSONObject(i);
            if (c == null) continue;
            String vid = c.optString("id");
            String vname = c.optString("name");
            pick(a, "◆ " + vname, vid).setOnLongClickListener(w -> {
                new Dialogs.Builder(a).setTitle(vname).setItems(new String[]{"Rename", "Delete", "Cancel"}, (d, which) -> {
                    if (which == 0) VirtualCategories.rename(a, vid, () -> { a.resumeCategory = vid; top(a); });
                    else if (which == 1) VirtualCategories.delete(a, vid, () -> { a.resumeCategory = ""; top(a); });
                }).show();
                return true;
            });
        }
        String resume = a.resumeCategory;
        a.resumeCategory = null;
        String active = a.library.activeId();
        boolean resumeHere = resume != null && isActiveCategory(a, resume);
        // Coming back to one of its categories: show it open, unless it was just closed on purpose.
        if (resumeHere && !closedOnPurpose) open.add(active);
        closedOnPurpose = false;
        JSONArray pls = a.library.inUse();
        android.widget.TextView header = null;
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null || Library.isHdhr(p)) continue; // tuners show as Antenna TV above
            String pid = p.optString("id");
            boolean on = pid.equals(active);
            boolean expanded = open.contains(pid);
            // Every switched-on playlist works the same: OK opens its categories right under it; OK again closes them.
            android.widget.TextView head = a.button((expanded ? "▼  " : "▶  ") + Library.nameOf(p), a.side, () -> togglePlaylist(a, p));
            head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            head.setTextColor(Theme.ACCENT);
            if (pid.equals(focusHeader)) { header = head; lastHeader = head; }
            if (expanded) { if (on) categoriesUnder(a); else otherCategoriesUnder(a, p); }
        }
        // Start in the menu (Favorites highlighted) unless coming back from a channel, which re-focuses that channel.
        a.previewOnly = a.resumeStreamId == null;
        if (a.previewOnly && a.side.getChildCount() > 1) a.side.getChildAt(1).requestFocus();
        a.loadItems(resume != null && (VirtualCategories.isVirtual(resume) || Antenna.isAntenna(resume) || resumeHere) ? resume : "fav");
        android.widget.TextView want = focusOnOpen == null ? null : picks.get(focusOnOpen);
        focusOnOpen = null;
        if (want != null && a.resumeStreamId == null) want.requestFocus();
        if (header != null && a.resumeStreamId == null) header.requestFocus();
        focusHeader = null;
        restyle(a);
    }

    /** Playlists whose categories are open under them in the menu; kept while the app runs. */
    private static final java.util.Set<String> open = new java.util.HashSet<>();
    /** Playlist heading to highlight once the menu is rebuilt (after opening or closing it). */
    private static String focusHeader;

    /** True for "All categories" ("") or one of the playing playlist's provider categories. */
    private static boolean isActiveCategory(MainActivity a, String id) {
        if (id.isEmpty()) return open.contains(a.library.activeId());
        if (a.categories == null) return false;
        for (int i = 0; i < a.categories.length(); i++) {
            JSONObject c = a.categories.optJSONObject(i);
            if (c != null && id.equals(c.optString("category_id"))) return true;
        }
        return false;
    }

    /** OK on a playlist heading: open or close its categories in place (switching to that playlist first if needed). */
    private static void togglePlaylist(MainActivity a, JSONObject p) {
        String pid = p.optString("id");
        focusHeader = pid;
        if (open.remove(pid)) closedOnPurpose = pid.equals(a.library.activeId()); else open.add(pid);
        a.resumeCategory = a.category; // keep the guide on whatever it shows now
        top(a);
        focusLater(a);
    }

    /** Live categories of playlists other than the one whose channels are loaded, by playlist id (kept while the app runs). */
    private static final java.util.Map<String, JSONArray> OTHER_CATS = new java.util.HashMap<>();
    private static final java.util.Set<String> OTHER_LOADING = new java.util.HashSet<>();

    /**
     * The categories of another switched-on playlist, under its heading, so every playlist can be opened at any time.
     * Picking one shows that category's channels (the app loads that playlist's channels behind the scenes).
     */
    private static void otherCategoriesUnder(MainActivity a, JSONObject p) {
        String pid = p.optString("id");
        JSONArray cats = OTHER_CATS.get(pid);
        if (cats == null) {
            a.button("      Loading…", a.side, () -> { });
            fetchOther(a, p);
            return;
        }
        java.util.Set<String> hidden = a.prefs.getStringSet(Library.prefixOf(p) + "hidden_categories_live", java.util.Collections.<String>emptySet());
        a.button("      All categories", a.side, () -> openOther(a, p, ""));
        for (int i = 0; i < cats.length(); i++) {
            JSONObject c = cats.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("category_id"), title = c.optString("category_name", "Category");
            if (hidden.contains(id)) continue; // hidden categories stay out
            a.button("      › " + title, a.side, () -> openOther(a, p, id));
        }
        if (cats.length() == 0) a.button("      No categories", a.side, () -> { });
    }

    private static void openOther(MainActivity a, JSONObject p, String categoryId) {
        Switching.show(a, p);
        open.add(p.optString("id"));
        tier = TOP;
        a.switchPlaylist(p);
        a.resumeCategory = categoryId;
        a.browse("live");
    }

    /** Loads another playlist's live categories in the background (Xtream from the server, M3U from its saved copy). */
    private static void fetchOther(MainActivity a, JSONObject p) {
        String pid = p.optString("id");
        if (!OTHER_LOADING.add(pid)) return;
        new Thread(() -> {
            JSONArray list = null;
            try {
                if (Library.M3U.equals(p.optString("type"))) {
                    list = new JSONArray();
                    for (String g : a.library.m3uOf(p).groups) list.put(new JSONObject().put("category_id", g).put("category_name", g));
                } else {
                    String text = Library.download(p.optString("host") + "/player_api.php?username=" + a.enc(p.optString("user"))
                            + "&password=" + a.enc(p.optString("pass")) + "&action=get_live_categories", 30000).trim();
                    list = text.startsWith("[") ? new JSONArray(text) : new JSONArray();
                }
            } catch (Throwable ignored) { }
            final JSONArray got = list;
            a.runOnUiThread(() -> {
                OTHER_LOADING.remove(pid);
                if (a.isFinishing() || a.isDestroyed()) return;
                if (got == null) {
                    android.widget.Toast.makeText(a, "Couldn't load " + Library.nameOf(p) + " categories", android.widget.Toast.LENGTH_LONG).show();
                    open.remove(pid);
                } else OTHER_CATS.put(pid, got);
                if ("live".equals(a.mode) && tier.equals(TOP)) { a.resumeCategory = a.category; focusHeader = pid; top(a); }
            });
        }).start();
    }

    /** Forgets other playlists' categories (after playlists change). */
    static void forgetOthers() { OTHER_CATS.clear(); }

    /** The playing playlist's categories, indented under its heading. */
    /** Closes the playing playlist's categories (as OK on the playlist does) and puts the focus back on the playlist. */
    private static void collapse(MainActivity a) {
        String pid = a.library.activeId();
        open.remove(pid);
        closedOnPurpose = true;
        focusHeader = pid;
        a.resumeCategory = a.category; // keep the guide on whatever it shows now
        top(a);
        focusLater(a);
    }

    /** Set while closing a playlist's categories, so the redraw doesn't open them again for the category on screen. */
    private static boolean closedOnPurpose;
    /** The playlist name the focus should be on after it was opened or closed. */
    private static android.widget.TextView lastHeader;

    /**
     * Puts the focus on the playlist name once the screen has settled: a closing pop-up hands focus back to whatever
     * had it before, which the redraw removed, so the name is focused again just after.
     */
    private static void focusLater(MainActivity a) {
        android.widget.TextView h = lastHeader;
        if (h == null) return;
        h.requestFocus();
        a.side.postDelayed(() -> { if (h.isAttachedToWindow() && h.isShown() && !h.isFocused()) h.requestFocus(); }, 150);
    }

    private static void categoriesUnder(MainActivity a) {
        java.util.Set<String> hidden = a.hiddenCategories();
        int hiddenCount = 0;
        UNDER.clear();
        UNDER.add(pick(a, "      All categories", ""));
        for (int i = 0; i < a.categories.length(); i++) {
            JSONObject c = a.categories.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("category_id"), title = c.optString("category_name", "Category");
            if (hidden.contains(id)) { hiddenCount++; continue; }
            android.widget.TextView cat = pick(a, "      › " + title, id);
            UNDER.add(cat);
            cat.setOnLongClickListener(w -> {
                new Dialogs.Builder(a).setTitle(title).setItems(new String[]{"Hide this category", "⊘  Hide / show channels…", "▲  Collapse categories", "Cancel"}, (d, which) -> {
                    // Ticks for this category's channels only; back in Live TV on this category afterwards.
                    if (which == 1) { Manage.channelsIn(a, id, title, () -> { a.resumeCategory = id; a.browse("live"); }); return; }
                    if (which == 2) { collapse(a); return; }
                    if (which != 0) return;
                    java.util.Set<String> h = a.hiddenCategories();
                    h.add(id);
                    a.saveHiddenCategories(h);
                    a.resumeCategory = a.category;
                    top(a);
                }).show();
                return true;
            });
        }
        UNDER.add(a.button(hiddenCount == 0 ? "      ◐ Hide / show categories" : "      ◐ Hide / show categories (" + hiddenCount + " hidden)", a.side, a::manageCategories));
    }

    /** The entries listed under the open playlist (its categories), so Back on one of them steps out to the playlist. */
    private static final java.util.Set<android.view.View> UNDER = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** The menu entry the focus last left from (into the channels, guide or posters); ◀ and Back return to it. */
    private static java.lang.ref.WeakReference<android.view.View> origin = new java.lang.ref.WeakReference<>(null);

    /** The origin entry, if it is still in the menu now on screen. */
    private static android.view.View originIn(android.view.View menu) {
        android.view.View v = origin.get();
        return v != null && v.isAttachedToWindow() && v.isFocusable() && inside(v, menu) ? v : null;
    }

    /**
     * Back on Live TV, Movies and Series. From the channels, guide or posters it returns to the menu entry you came
     * from; on a category under an open playlist it closes the categories and goes back to the playlist.
     * Returns true when the key was used.
     */
    static boolean backToMenu(MainActivity a) {
        if (menuScroll == null || !menuScroll.isShown()) return false;
        android.view.View f = a.getCurrentFocus();
        if (f == null) return false;
        boolean inMenu = inside(f, menuScroll) || (parentScroll != null && parentScroll.isShown() && inside(f, parentScroll));
        if (!inMenu && a.root != null && inside(f, a.root)) {
            android.view.View o = originIn(menuScroll);
            if (o == null && a.side.getChildCount() > 1) o = a.side.getChildAt(1);
            if (o != null) { o.requestFocus(); return true; }
        }
        if ("live".equals(a.mode) && tier.equals(TOP) && UNDER.contains(f) && f.isAttachedToWindow()) {
            collapse(a);
            return true;
        }
        return false;
    }

    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable pending;

    /**
     * A menu entry whose channels show in the guide on the right as soon as it is highlighted (after a short pause,
     * so scrolling through the menu stays quick). OK on it moves into the guide.
     */
    static android.widget.TextView pick(MainActivity a, String label, String id) {
        android.widget.TextView v = a.button(label, a.side, () -> {
            if (pending != null) UI.removeCallbacks(pending);
            a.previewOnly = false;
            a.loadItems(id);
        });
        android.view.View.OnFocusChangeListener old = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener((w, f) -> {
            if (old != null) old.onFocusChange(w, f);
            if (!f) return;
            if (pending != null) UI.removeCallbacks(pending);
            if (id.equals(a.category)) return;
            pending = () -> { if (v.hasFocus() && !id.equals(a.category)) { a.previewOnly = true; a.loadItems(id); } };
            UI.postDelayed(pending, 450);
        });
        picks.put(id, v);
        return v;
    }

    /** Second tier: the playlist's provider categories (switching to it first if needed). */
    static void openPlaylist(MainActivity a, JSONObject p) {
        tier = CATEGORIES;
        if (p.optString("id").equals(a.library.activeId()) && a.categories.length() > 0) {
            a.showCategories();
            if (a.side.getChildCount() > 1) a.side.getChildAt(1).requestFocus();
            return;
        }
        a.switchPlaylist(p);
        a.browse("live");
    }

    // ---------------------------------------------------------------- first tier kept on the far left

    /** The narrow first-tier column shown on the far left while a playlist's categories are open. */
    private static android.widget.ScrollView parentScroll;
    private static android.widget.LinearLayout parentCol;
    /** The compact entry of the playlist whose categories are open (Left from the categories lands here). */
    private static android.widget.TextView parentCurrent;
    /** The category that had the highlight when Left moved into the compact column (Right returns to it). */
    private static android.view.View lastCategory;

    /** Called by browse("live"): the (hidden) column that holds the compacted first tier. */
    static android.view.View buildParentColumn(MainActivity a) {
        parentCol = new android.widget.LinearLayout(a);
        parentCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        parentScroll = new android.widget.ScrollView(a);
        parentScroll.setTag(FocusWrap.MENU); // Up/Down stay in it, wrapping at the ends
        parentScroll.setFillViewport(true);
        parentScroll.setVerticalScrollBarEnabled(false);
        parentScroll.addView(parentCol);
        parentScroll.setVisibility(android.view.View.GONE);
        parentCurrent = null;
        lastCategory = null;
        parentCollapsed = false;
        return parentScroll;
    }

    private static void hideParent() {
        if (parentScroll != null) parentScroll.setVisibility(android.view.View.GONE);
        if (parentCol != null) parentCol.removeAllViews();
        parentCurrent = null;
        lastCategory = null;
    }

    /**
     * Second tier is showing: keep the first tier on the far left as a narrow column (short labels, the open
     * playlist marked), so Left goes back to it and OK on any entry opens that one.
     */
    static void showParent(MainActivity a) {
        if (parentScroll == null || parentCol == null) return;
        parentCol.removeAllViews();
        parentCurrent = null;
        lastCategory = null;
        compact(a, "←", a.liveOnly() ? "TV Guide" : "Menu", false, () -> backOut(a));
        compact(a, "★", "Favorites", false, () -> backTo(a, "fav"));
        compact(a, "⌕", "Search", false, a::searchLive);
        if (!Brand.OTA && TabloApp.isOn(a.prefs)) compact(a, "📡", TabloApp.nameOf(a.prefs), false, () -> TabloApp.open(a));
        JSONArray mine = VirtualCategories.all(a);
        for (int i = 0; i < mine.length(); i++) {
            JSONObject c = mine.optJSONObject(i);
            if (c == null) continue;
            String vid = c.optString("id");
            compact(a, "◆", c.optString("name"), false, () -> backTo(a, vid));
        }
        JSONArray pls = a.library.inUse();
        String active = a.library.activeId();
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null || Library.isHdhr(p)) continue;
            boolean on = p.optString("id").equals(active);
            android.widget.TextView v = compact(a, on ? "▤" : "▢", Library.nameOf(p), on, () -> {
                if (on) toCategories(a); else openPlaylist(a, p);
            });
            if (on) parentCurrent = v;
        }
        parentScroll.setVisibility(android.view.View.VISIBLE);
        // Focus goes on into the categories, so this column starts as a strip of icons (it opens when you move into it).
        boolean wasCollapsed = parentCollapsed;
        setParentCollapsed(a, true);
        if (wasCollapsed) iconize(a, parentCol, true);
        if (parentCurrent != null) {
            android.widget.TextView cur = parentCurrent;
            parentScroll.post(() -> parentScroll.requestChildRectangleOnScreen(parentCol,
                    new android.graphics.Rect(0, cur.getTop(), cur.getWidth(), cur.getBottom()), true));
        }
    }

    /** One short entry in the compact first-tier column. */
    private static android.widget.TextView compact(MainActivity a, String icon, String name, boolean current, Runnable ok) {
        android.widget.TextView v = a.button(icon + "  " + name, parentCol, ok);
        v.setTextSize(16);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setPadding(a.dp(10), a.dp(9), a.dp(6), a.dp(9));
        android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) v.getLayoutParams();
        lp.setMargins(a.dp(4), a.dp(3), a.dp(4), a.dp(3));
        v.setLayoutParams(lp);
        if (current) {
            v.setTextColor(Theme.ACCENT);
            v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else v.setTextColor(Theme.MUTED);
        android.view.View.OnFocusChangeListener old = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener((w, f) -> {
            if (old != null) old.onFocusChange(w, f);
            if (!current) v.setTextColor(f ? 0xffffffff : Theme.MUTED);
        });
        return v;
    }

    /** OK on Favorites, an Antenna tuner or one of your categories in the compact column: back to the first tier on it. */
    private static void backTo(MainActivity a, String id) {
        a.resumeCategory = id;
        focusOnOpen = id;
        top(a);
    }

    /** Right from the compact column (or OK on the open playlist): back into the categories. */
    private static void toCategories(MainActivity a) {
        if (lastCategory != null && lastCategory.getParent() == a.side && lastCategory.isShown()) lastCategory.requestFocus();
        else if (a.side.getChildCount() > 1) a.side.getChildAt(1).requestFocus();
    }

    /**
     * Left/Right between the compact first tier and the categories. Called from TvRemote before other keys.
     * Returns true when the key was used.
     */
    static boolean sideways(MainActivity a, android.view.KeyEvent e) {
        int key = e.getKeyCode();
        if (key != android.view.KeyEvent.KEYCODE_DPAD_LEFT && key != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (!"live".equals(a.mode) || !tier.equals(CATEGORIES) || parentScroll == null || !parentScroll.isShown()) return false;
        android.view.View f = a.getCurrentFocus();
        if (f == null) return false;
        boolean left = key == android.view.KeyEvent.KEYCODE_DPAD_LEFT;
        if (left && f.getParent() == a.side) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                lastCategory = f;
                if (parentCurrent != null) parentCurrent.requestFocus();
                else if (parentCol.getChildCount() > 0) parentCol.getChildAt(0).requestFocus();
            }
            return true;
        }
        if (!left && f.getParent() == parentCol) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) toCategories(a);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- menus shrink to icons, like the main menu

    /** Width of the compact first-tier column while it has the focus. */
    static final int NARROW_DP = 150;
    /** Width of a menu column that doesn't have the focus: icons only, like the main menu on the far left. */
    static final int ICON_DP = 72;
    private static android.view.View menuScroll;
    /** Whether the category menu / the compact first-tier column are showing icons only right now. */
    private static boolean collapsed, parentCollapsed;

    /**
     * Called by browse() (Live TV, Movies and Series) with the menu column. Each menu column works like the main
     * menu: while it has the focus it shows full labels; once focus moves on (into the next menu, the guide or the
     * preview) it shrinks to a strip of icons, and moving back into it opens it again. Focus on the main menu
     * itself leaves them as they are, since that menu opens over the screen.
     */
    static void watchFocus(MainActivity a, android.view.View categoryScroll) {
        menuScroll = categoryScroll;
        collapsed = false;
        String screen = a.mode; // Live TV, Movies or Series
        android.view.View content = a.root;
        android.view.ViewTreeObserver.OnGlobalFocusChangeListener track = (oldFocus, newFocus) -> {
            if (newFocus == null || menuScroll != categoryScroll || !screen.equals(a.mode)) return;
            if (content != null && !inside(newFocus, content)) return; // the main menu, a dialog…
            boolean inParent = parentScroll != null && parentScroll.isShown() && inside(newFocus, parentScroll);
            boolean inMenu = inside(newFocus, categoryScroll);
            if (oldFocus != null && oldFocus != newFocus) {
                boolean wasInMenu = inside(oldFocus, categoryScroll);
                if (wasInMenu && !inMenu && !inParent) origin = new java.lang.ref.WeakReference<>(oldFocus); // left the menu for the channels
                else if (inMenu && !wasInMenu && inside(oldFocus, content)
                        && !(parentScroll != null && inside(oldFocus, parentScroll))) {
                    // Coming back from the channels (◀): land on the entry you left from, not the nearest one.
                    android.view.View o = originIn(categoryScroll);
                    if (o != null && o != newFocus) categoryScroll.post(o::requestFocus);
                }
            }
            setCollapsed(a, !inMenu);
            setParentCollapsed(a, !inParent);
        };
        android.view.View.OnAttachStateChangeListener attach = new android.view.View.OnAttachStateChangeListener() {
            android.view.ViewTreeObserver observer;
            @Override public void onViewAttachedToWindow(android.view.View v) {
                if (observer != null && observer.isAlive()) return;
                observer = v.getViewTreeObserver();
                observer.addOnGlobalFocusChangeListener(track);
            }
            @Override public void onViewDetachedFromWindow(android.view.View v) { if (observer != null && observer.isAlive()) observer.removeOnGlobalFocusChangeListener(track); observer = null; }
        };
        categoryScroll.addOnAttachStateChangeListener(attach);
        // The column is usually on screen already by now (the window was showing another screen), and then the
        // attach callback above never comes: start watching straight away.
        if (categoryScroll.isAttachedToWindow()) attach.onViewAttachedToWindow(categoryScroll);
    }

    private static boolean inside(android.view.View v, android.view.View group) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == group) return true;
        return v == group;
    }

    /** Shrinks the category menu to a strip of icons, or opens it back to its full width and labels. */
    private static void setCollapsed(MainActivity a, boolean on) {
        if (collapsed == on || menuScroll == null) return;
        collapsed = on;
        android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) menuScroll.getLayoutParams();
        lp.width = on ? a.dp(ICON_DP) : 0;
        lp.weight = on ? 0 : 1;
        menuScroll.setLayoutParams(lp);
        iconize(a, a.side, on);
    }

    /** The same for the compact first-tier column (Live TV, while a playlist's categories are open). */
    private static void setParentCollapsed(MainActivity a, boolean on) {
        if (parentCollapsed == on || parentScroll == null) return;
        parentCollapsed = on;
        android.view.ViewGroup.LayoutParams lp = parentScroll.getLayoutParams();
        if (lp != null) {
            lp.width = a.dp(on ? ICON_DP : NARROW_DP);
            parentScroll.setLayoutParams(lp);
        }
        if (parentCol != null) iconize(a, parentCol, on);
    }

    /** How a menu entry looked before it was shrunk to its icon, so it can be put back exactly. */
    private static final class Full {
        final CharSequence text; final float size; final int l, t, r, b, gravity, maxLines;
        final android.text.TextUtils.TruncateAt ellipsize;
        Full(android.widget.TextView v) {
            text = v.getText(); size = v.getTextSize(); gravity = v.getGravity(); maxLines = v.getMaxLines(); ellipsize = v.getEllipsize();
            l = v.getPaddingLeft(); t = v.getPaddingTop(); r = v.getPaddingRight(); b = v.getPaddingBottom();
        }
        void restore(android.widget.TextView v) {
            v.setText(text);
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
            v.setGravity(gravity);
            v.setMaxLines(maxLines);
            v.setEllipsize(ellipsize);
            v.setPadding(l, t, r, b);
        }
    }
    private static final java.util.WeakHashMap<android.widget.TextView, Full> FULL = new java.util.WeakHashMap<>();

    /** Every entry of a menu column as just its icon (on), or with its full label back (off). */
    private static void iconize(MainActivity a, android.view.ViewGroup col, boolean on) {
        java.util.List<android.widget.TextView> entries = new java.util.ArrayList<>();
        for (int i = 0; i < col.getChildCount(); i++)
            if (col.getChildAt(i) instanceof android.widget.TextView) entries.add((android.widget.TextView) col.getChildAt(i));
        if (!on) {
            for (android.widget.TextView v : entries) { Full f = FULL.remove(v); if (f != null) f.restore(v); }
            return;
        }
        // Icons used by more than one entry (◆ for each of your categories, › for each provider category)
        // get the name's first letter too, so the strip still tells them apart.
        java.util.Map<String, Integer> uses = new java.util.HashMap<>();
        java.util.List<String> fulls = new java.util.ArrayList<>();
        for (android.widget.TextView v : entries) {
            Full f = FULL.get(v);
            String text = String.valueOf(f != null ? f.text : v.getText());
            fulls.add(text);
            String icon = MenuIcons.iconOf(text);
            if (!icon.isEmpty()) { Integer n = uses.get(icon); uses.put(icon, n == null ? 1 : n + 1); }
        }
        for (int i = 0; i < entries.size(); i++) {
            android.widget.TextView v = entries.get(i);
            if (!FULL.containsKey(v)) FULL.put(v, new Full(v));
            String icon = MenuIcons.iconOf(fulls.get(i));
            Integer n = uses.get(icon);
            v.setText(MenuIcons.iconLabel(fulls.get(i), n != null && n > 1));
            v.setTextSize(19);
            v.setGravity(android.view.Gravity.CENTER);
            v.setMaxLines(1);
            v.setEllipsize(null);
            v.setPadding(0, a.dp(10), 0, a.dp(10));
        }
    }

    /** Entries added while a menu is shrunk (the menu was rebuilt) get the icon look too. */
    static void restyle(MainActivity a) {
        if (collapsed && a.side != null) iconize(a, a.side, true);
        if (parentCollapsed && parentCol != null) iconize(a, parentCol, true);
    }

    /** Back in Live TV: from the categories tier go up to the first tier. False when already at the top. */
    static boolean back(MainActivity a) {
        if (tier.equals(TOP)) return false;
        a.resumeCategory = null;
        top(a);
        return true;
    }

    /** Favorites of every saved playlist, current one first, as mini-guide rows. */
    static void favorites(MainActivity a) {
        a.listGeneration++;
        a.category = "fav";
        Map<String, String> icons = new HashMap<>();
        if (a.activeLive != null) for (int i = 0; i < a.activeLive.length(); i++) {
            JSONObject it = a.activeLive.optJSONObject(i);
            if (it != null) icons.put(it.optString("stream_id"), it.optString("stream_icon"));
        }
        Map<String, ?> stored = a.prefs.getAll();
        JSONArray out = new JSONArray();
        JSONArray pls = a.library.inUse();
        String active = a.library.activeId();
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null || p.optString("id").equals(active) != (pass == 0)) continue;
            String pre = Library.prefixOf(p), head = pre + "fav_live_";
            for (Map.Entry<String, ?> e : stored.entrySet()) {
                if (!e.getKey().startsWith(head)) continue;
                String id = e.getKey().substring(head.length()), title = String.valueOf(e.getValue());
                if (title.isEmpty() || a.library.hiddenAntenna(p.optString("id"), id)) continue; // hidden antenna channel
                Object ext = stored.get(pre + "ext_live_" + id);
                try {
                    JSONObject it = new JSONObject().put("stream_id", id).put("name", title)
                            .put("container_extension", ext == null || String.valueOf(ext).isEmpty() ? "ts" : String.valueOf(ext));
                    if (pass == 0) { String icon = icons.get(id); if (icon != null) it.put("stream_icon", icon); }
                    else it.put("playlist", p.optString("id"));
                    out.put(it);
                } catch (JSONException ignored) { }
            }
        }
        a.items = out;
        if (out.length() == 0) Toast.makeText(a, "No favorites yet. Hold OK on a channel and choose Add Favorite.", Toast.LENGTH_LONG).show();
        a.showItems();
    }

    /** OK on a favorite: switch to its playlist if it's another one, then play, zapping through that playlist's favorites. */
    static void playFavorite(MainActivity a, JSONObject item) {
        String pid = item.optString("playlist");
        JSONArray zap = new JSONArray();
        for (int i = 0; i < a.items.length(); i++) {
            JSONObject it = a.items.optJSONObject(i);
            if (it != null && it.optString("playlist").equals(pid)) zap.put(it);
        }
        if (!VirtualCategories.useItsPlaylist(a, item)) return;
        a.zapList = zap;
        String ext = item.optString("container_extension", "ts");
        a.play("live", item.optString("stream_id"), ext.isEmpty() ? "ts" : ext, item.optString("name", "Untitled"));
    }
}
