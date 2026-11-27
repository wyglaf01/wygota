package com.wygtv.tv;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps playlists and TV guides up to date: loads a playlist and its guide right when it is added, updates
 * everything on start-up or every 6/12/24 hours (Settings > Manage playlists), and shows how it is going in
 * the status line at the bottom of every screen, or on a progress screen when "wait" is chosen.
 *
 * Updates run one at a time on their own thread; a new one waits behind the one running.
 */
final class AutoRefresh {
    /** How often: "start" (every start-up), "6" / "12" / "24" hours, or "manual" (only when asked). */
    static final String PREF_EVERY = "refresh_every";
    static final String[] EVERY_KEYS = {"start", "6", "12", "24", "manual"};
    static final String[] EVERY_LABELS = {"Every time WygTV starts", "Every 6 hours", "Every 12 hours", "Every 24 hours", "Only when I press Update now"};
    static final String EVERY_DEFAULT = "12";
    /** true (default): a new playlist or guide link loads right away. false: it loads the first time it is opened. */
    static final String PREF_ON_ADD = "refresh_on_add";
    /** "background" (default): keep using WygTV while it updates. "wait": show a progress screen until it's done. */
    static final String PREF_STYLE = "refresh_style";
    static final String[] STYLE_KEYS = {"background", "wait"};
    static final String[] STYLE_LABELS = {"In the background (keep watching)", "Show a progress screen and wait"};
    /** When the last full update finished, and what went wrong in it ("" = nothing). */
    static final String PREF_LAST = "refresh_last", PREF_PROBLEM = "refresh_problem";

    private static final long TICK = 10 * 60_000L;

    /** One thing to load: a playlist's channels or a guide. Returns "" when fine, else a short problem. */
    interface Work { String run() throws Exception; }

    static final class Step {
        final String label;
        final Work work;
        Step(String label, Work work) { this.label = label; this.work = work; }
    }

    private static final class Job {
        final String title;
        final List<Step> steps;
        final boolean full;
        final Runnable done;
        Job(String title, List<Step> steps, boolean full, Runnable done) { this.title = title; this.steps = steps; this.full = full; this.done = done; }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wygtv-refresh");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    // What the status line and progress screen show. Written on the worker, read on the UI thread.
    private static volatile Job current;
    private static volatile int stepAt;
    private static final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile String stepLabel = "";
    private static final List<String> log = new ArrayList<>();
    private static MainActivity activity;
    /** The status text this class last painted, so it only replaces its own text, never a screen's message. */
    private static String painted = "";
    // Progress screen widgets (null when it isn't showing).
    private static TextView pageTitle, pageStep, pageLog;
    private static ProgressBar pageBar;
    private static int pageGeneration = -1;

    private AutoRefresh() { }

    // ------------------------------------------------------------------ settings

    static String every(SharedPreferences p) { String e = p.getString(PREF_EVERY, EVERY_DEFAULT); return Menus.indexOf(EVERY_KEYS, e) == 0 && !e.equals("start") ? EVERY_DEFAULT : e; }

    static boolean onAdd(SharedPreferences p) { return p.getBoolean(PREF_ON_ADD, true); }

    static boolean waits(SharedPreferences p) { return "wait".equals(p.getString(PREF_STYLE, "background")); }

    /**
     * How old a saved guide may get before it is downloaded again when shown: the update interval, so guides
     * aren't fetched more often than asked. "Only when I press" keeps them until Update now.
     */
    static long maxAge(SharedPreferences p) {
        String e = every(p);
        if (e.equals("manual")) return Long.MAX_VALUE;
        if (e.equals("start")) return 24 * 3600_000L;
        try { return Long.parseLong(e) * 3600_000L; } catch (NumberFormatException x) { return 12 * 3600_000L; }
    }

    static boolean running() { return current != null; }

    // ------------------------------------------------------------------ start-up and timer

    /**
     * Called as WygTV starts. Starts the update timer and, when an update is due, the update itself. Returns
     * true when the progress screen took over: {@code then} opens the start-up screen once it's done.
     */
    static boolean atStart(MainActivity a, Runnable then) {
        activity = a;
        startTimer(a);
        if (a.library.isEmpty()) return false;
        String e = every(a.prefs);
        if (e.equals("manual")) return false;
        boolean due = e.equals("start") || dueByTime(a.prefs);
        if (!due) return false;
        boolean wait = waits(a.prefs);
        updateAll(a, wait ? then : null);
        return wait;
    }

    private static boolean dueByTime(SharedPreferences p) {
        String e = every(p);
        if (e.equals("manual") || e.equals("start")) return false;
        return System.currentTimeMillis() - p.getLong(PREF_LAST, 0) >= maxAge(p);
    }

    private static Runnable timer;

    /** While WygTV is open, checks every 10 minutes whether the next update is due (always in the background). */
    private static void startTimer(MainActivity a) {
        if (timer != null) UI.removeCallbacks(timer);
        timer = new Runnable() {
            @Override public void run() {
                MainActivity at = activity;
                if (at == null || at.isFinishing() || at.isDestroyed()) return;
                if (!running() && !at.library.isEmpty() && dueByTime(at.prefs)) updateAll(at, null);
                UI.postDelayed(this, TICK);
            }
        };
        UI.postDelayed(timer, TICK);
    }

    // ------------------------------------------------------------------ jobs

    /**
     * Updates every switched-on playlist and every guide. {@code then} != null: show the progress screen
     * and run it when done (or when the viewer chooses to continue in the background).
     */
    static void updateAll(MainActivity a, Runnable then) {
        a.library.dropLiveSearchCache(); // search downloads the fresh channel lists next time
        Showcase.forget(a); // the main screen's popular rows are looked up again
        List<Step> steps = new ArrayList<>();
        JSONArray on = a.library.inUse();
        for (int i = 0; i < on.length(); i++) { JSONObject p = on.optJSONObject(i); if (p != null) addPlaylist(a, steps, p, true); }
        addExtraGuides(a, steps, true);
        run(a, "Updating playlists and TV guides", steps, true, then);
    }

    /**
     * One playlist, from its Refresh now: channels or account, then its guide. {@code after} redraws the
     * screen it was started from.
     */
    static void updateOne(MainActivity a, JSONObject p, Runnable after) {
        a.library.dropLiveSearchCache(); // search downloads the fresh channel lists next time
        List<Step> steps = new ArrayList<>();
        addPlaylist(a, steps, p, true);
        boolean wait = waits(a.prefs);
        run(a, "Updating " + Library.nameOf(p), steps, false, wait ? after : null);
        if (!wait) after.run();
    }

    /**
     * A playlist was just added (its channels are already loaded): load its guide and any extra guides not
     * loaded yet, unless Settings says to wait until they're needed. {@code then} opens the next screen.
     */
    static void added(MainActivity a, JSONObject p, Runnable then) {
        activity = a;
        if (!onAdd(a.prefs)) { then.run(); return; }
        List<Step> steps = new ArrayList<>();
        addPlaylist(a, steps, p, false);
        addExtraGuides(a, steps, false);
        if (steps.isEmpty()) { stampPlaylist(a, p.optString("id"), ""); then.run(); return; }
        boolean wait = waits(a.prefs);
        run(a, "Loading " + Library.nameOf(p), steps, false, wait ? then : null);
        if (!wait) then.run();
    }

    /** An extra guide link was just added: load it now, unless Settings says to wait. {@code now}: Reload now, always load. */
    static void guideAdded(MainActivity a, String url, Runnable redraw, boolean now) {
        activity = a;
        if (!now && !onAdd(a.prefs)) { redraw.run(); Toast.makeText(a, "Saved. The guide loads the first time it's needed.", Toast.LENGTH_LONG).show(); return; }
        List<Step> steps = new ArrayList<>();
        steps.add(extraStep(a, url, true));
        boolean wait = waits(a.prefs);
        run(a, "Loading a TV guide", steps, false, wait ? redraw : null);
        if (!wait) { redraw.run(); Toast.makeText(a, "Loading the guide in the background. The status line shows how it's going.", Toast.LENGTH_LONG).show(); }
    }

    /** The steps for one playlist. {@code channels}: download its channels / check its account first. */
    private static void addPlaylist(MainActivity a, List<Step> steps, JSONObject p, boolean channels) {
        final String id = p.optString("id");
        final String name = Library.nameOf(p);
        final boolean m3u = Library.M3U.equals(p.optString("type"));
        if (channels) {
            steps.add(new Step((m3u ? "Channels: " : "Account: ") + name, () -> {
                JSONObject cur = a.library.find(id);
                if (cur == null) return "";
                String problem = "";
                try {
                    if (m3u) {
                        M3u.Playlist got = a.library.downloadM3u(cur.optString("url"));
                        cur.put("count", got.channels.size());
                    } else {
                        JSONObject info = new JSONObject(Library.download(cur.optString("host") + "/player_api.php?username=" + XtreamProtocol.encode(cur.optString("user"))
                                + "&password=" + XtreamProtocol.encode(cur.optString("pass")), 20000)).optJSONObject("user_info");
                        if (info == null || info.optInt("auth", 0) != 1) problem = "login rejected";
                        else {
                            cur.put("expiry", info.optString("exp_date", ""));
                            String st = info.optString("status", "Active");
                            if (!st.equalsIgnoreCase("active")) problem = "account " + st.toLowerCase(java.util.Locale.ROOT);
                        }
                    }
                } catch (InterruptedException stop) { throw stop; }
                catch (Exception e) { problem = "couldn't reach " + (m3u ? "the playlist link" : "the server"); }
                cur.put("updated", System.currentTimeMillis()).put("update_problem", problem);
                a.library.put(cur);
                if (id.equals(a.library.activeId())) a.library.activate(cur);
                return problem;
            }));
        }
        // Its own guide: the active playlist's guide (provider XMLTV link, M3U guide or HDHomeRun guide), or,
        // for another M3U playlist, the guide link it names (read through the extra-guide store).
        steps.add(new Step("TV guide: " + name, () -> {
            JSONObject cur = a.library.find(id);
            if (cur == null) return "";
            String problem;
            if (id.equals(a.library.activeId())) problem = a.library.reloadGuide();
            else if (Library.M3U.equals(cur.optString("type")) && !Library.isHdhr(cur)) {
                String url = cur.optString("epg");
                if (url.isEmpty()) try { url = a.library.m3uOf(cur).guideUrl; } catch (Exception ignored) { }
                problem = url.isEmpty() ? "" : a.library.extras.reload(url);
            } else problem = "";
            if (!problem.isEmpty()) problem = "guide " + problem;
            stampPlaylist(a, id, problem);
            return problem;
        }));
    }

    /** Remembers when a playlist was last updated; keeps an earlier step's problem unless this one has its own. */
    private static void stampPlaylist(MainActivity a, String id, String problem) {
        JSONObject cur = a.library.find(id);
        if (cur == null) return;
        try {
            String before = cur.optString("update_problem");
            long at = cur.optLong("updated", 0);
            boolean sameRun = System.currentTimeMillis() - at < 10 * 60_000L;
            cur.put("updated", System.currentTimeMillis()).put("update_problem", problem.isEmpty() && sameRun ? before : problem);
        } catch (Exception ignored) { }
        a.library.put(cur);
        if (id.equals(a.library.activeId())) a.library.activate(cur);
    }

    /** Extra guides (Settings > Extra TV guides). {@code all}: every one; else only those not loaded yet. */
    private static void addExtraGuides(MainActivity a, List<Step> steps, boolean all) {
        for (String url : a.library.extras.urls()) if (all || a.library.extras.status(url).isEmpty()) steps.add(extraStep(a, url, all));
    }

    private static Step extraStep(MainActivity a, String url, boolean force) {
        return new Step("Extra guide: " + Menus.hostOf(url), () -> {
            if (force) return a.library.extras.reload(url);
            return a.library.extras.guide(url) != null ? "" : a.library.extras.status(url);
        });
    }

    private static void run(MainActivity a, String title, List<Step> steps, boolean full, Runnable waitThen) {
        activity = a;
        Job job = new Job(title, steps, full, waitThen);
        queued.incrementAndGet();
        if (waitThen != null) showPage(a, job);
        paint();
        WORKER.execute(() -> work(job));
    }

    private static void work(Job job) {
        current = job;
        queued.decrementAndGet();
        synchronized (log) { log.clear(); }
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < job.steps.size(); i++) {
            Step s = job.steps.get(i);
            stepAt = i;
            stepLabel = s.label;
            UI.post(AutoRefresh::progress);
            String problem;
            try { problem = s.work.run(); } catch (Throwable e) { problem = "couldn't load (" + e.getClass().getSimpleName() + ")"; }
            if (problem == null) problem = "";
            if (!problem.isEmpty()) problems.add(s.label.replaceFirst("^[^:]*: ", "") + ": " + problem);
            synchronized (log) { log.add((problem.isEmpty() ? "✓  " : "⚠  ") + s.label + (problem.isEmpty() ? "" : "  •  " + problem)); }
        }
        MainActivity a = activity;
        if (a != null) {
            String summary = problems.isEmpty() ? "" : problems.size() == 1 ? problems.get(0) : problems.get(0) + " (+" + (problems.size() - 1) + " more)";
            SharedPreferences.Editor ed = a.prefs.edit();
            if (job.full) ed.putLong(PREF_LAST, System.currentTimeMillis());
            if (job.full || !summary.isEmpty()) ed.putString(PREF_PROBLEM, summary);
            ed.apply();
        }
        current = null;
        UI.post(() -> finished(job));
    }

    private static void finished(Job job) {
        MainActivity a = activity;
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        // Guide rows already on screen keep what they show; the next ones drawn read the new guides.
        MiniGuide.clear();
        boolean onPage = pageTitle != null && pageGeneration == a.screenGeneration && "refreshing".equals(a.mode);
        String problem = a.prefs.getString(PREF_PROBLEM, "");
        if (onPage && job.done != null) {
            pageTitle = null;
            job.done.run();
        } else if (job.done == null) {
            Toast.makeText(a, problem.isEmpty() || !job.full ? (job.title.startsWith("Loading") ? "Loaded." : "Up to date.") : "Updated, with a problem: " + problem, Toast.LENGTH_SHORT).show();
            // Redraw the screens that show update times, so they don't stay on "updating…".
            if ("playlists".equals(a.mode)) Menus.playlists(a);
        }
        paint();
    }

    // ------------------------------------------------------------------ status line

    /** The status line's update part: "updating 2 of 5: …", "updated 3:14 PM", or the last problem. */
    static String statusText(MainActivity a) {
        Job j = current;
        if (j != null) return "⟳ " + (j.steps.isEmpty() ? j.title : "Updating " + (stepAt + 1) + " of " + j.steps.size() + ": " + stepLabel) + (queued.get() > 0 ? "  (+" + queued.get() + " waiting)" : "");
        if (queued.get() > 0) return "⟳ Update starting…";
        long last = a.prefs.getLong(PREF_LAST, 0);
        String problem = a.prefs.getString(PREF_PROBLEM, "");
        if (!problem.isEmpty()) return "⚠ Update problem: " + problem;
        return last == 0 ? "" : "Updated " + when(a, last);
    }

    /** Settings summary for the update row. */
    static String summary(MainActivity a) {
        String s = statusText(a);
        long last = a.prefs.getLong(PREF_LAST, 0);
        if (s.isEmpty()) s = "not updated yet";
        else if (s.startsWith("⚠") && last > 0) s = "last update " + when(a, last) + "  •  " + s;
        return s;
    }

    /** "just now", "3:14 PM", or "Nov 22, 3:14 PM". */
    static String when(MainActivity a, long t) {
        long now = System.currentTimeMillis();
        if (now - t < 60_000L) return "just now";
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(a);
        java.util.Calendar c = java.util.Calendar.getInstance(), d = java.util.Calendar.getInstance();
        d.setTimeInMillis(t);
        boolean today = c.get(java.util.Calendar.YEAR) == d.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.DAY_OF_YEAR) == d.get(java.util.Calendar.DAY_OF_YEAR);
        String clock = time.format(new java.util.Date(t));
        return today ? clock : new java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(new java.util.Date(t)) + ", " + clock;
    }

    /** One playlist's update line for the Playlists screen: "updated 3:14 PM" or "⚠ couldn't reach the server". */
    static String playlistLine(MainActivity a, JSONObject p) {
        String problem = p.optString("update_problem");
        long at = p.optLong("updated", 0);
        if (!problem.isEmpty()) return "⚠ " + problem;
        return at == 0 ? "" : "updated " + when(a, at);
    }

    private static String base = "";

    /** Sets the status line: the screen's own text, then the update part. */
    static void show(MainActivity a, String screenText) {
        activity = a;
        base = screenText;
        String up = statusText(a);
        painted = screenText.isEmpty() ? up : up.isEmpty() ? screenText : screenText + "  •  " + up;
        a.status.setText(painted);
    }

    private static void paint() {
        MainActivity a = activity;
        if (a == null || a.status == null) return;
        // Only replace the line this class wrote, never a screen's own message (e.g. "Loading the TV guide…").
        if (a.status.getText().toString().equals(painted)) show(a, base);
    }

    private static void progress() {
        paint();
        MainActivity a = activity;
        if (pageTitle == null || a == null || pageGeneration != a.screenGeneration) { pageTitle = null; return; }
        Job j = current;
        if (j == null) return;
        int total = Math.max(1, j.steps.size());
        pageBar.setMax(total);
        pageBar.setProgress(stepAt);
        pageStep.setText((stepAt + 1) + " of " + j.steps.size() + "  •  " + stepLabel + "…");
        StringBuilder sb = new StringBuilder();
        synchronized (log) { for (String l : log) sb.append(l).append('\n'); }
        pageLog.setText(sb.toString().trim());
    }

    // ------------------------------------------------------------------ progress screen

    private static void showPage(MainActivity a, Job job) {
        a.mode = "refreshing";
        a.shell("UPDATING");
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(a.dp(120), a.dp(20), a.dp(120), 0);
        a.body.addView(col, new LinearLayout.LayoutParams(-1, -1));
        pageTitle = a.label(job.title + "…", 24);
        pageTitle.setTextColor(Theme.TEXT);
        pageTitle.setGravity(Gravity.CENTER);
        col.addView(pageTitle, new LinearLayout.LayoutParams(-1, a.dp(44)));
        pageBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pageBar.setMax(Math.max(1, job.steps.size()));
        pageBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.COPPER));
        pageBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Theme.alpha(Theme.TEXT, 0x40)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, a.dp(10));
        bp.setMargins(0, a.dp(8), 0, a.dp(8));
        col.addView(pageBar, bp);
        pageStep = a.label(running() || queued.get() > 1 ? "Waiting for the update that's running…" : "Starting…", 16);
        pageStep.setTextColor(Theme.ACCENT);
        pageStep.setGravity(Gravity.CENTER);
        col.addView(pageStep, new LinearLayout.LayoutParams(-1, a.dp(34)));
        pageLog = a.label("", 14);
        pageLog.setTextColor(Theme.MUTED);
        pageLog.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        col.addView(pageLog, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView bg = a.button("Continue in the background", col, () -> { pageTitle = null; a.mode = ""; job.done.run(); });
        bg.setGravity(Gravity.CENTER);
        bg.requestFocus();
        pageGeneration = a.screenGeneration;
    }
}
