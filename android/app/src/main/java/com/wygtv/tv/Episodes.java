package com.wygtv.tv;

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * A series' seasons and episodes with watch progress: a Continue / Next episode button, "3/10 watched" on each
 * season, and ✓ (watched) or ◐ (started, with time left) on each episode. Unfinished episodes resume where they
 * stopped. Hold OK (or Menu) on an episode or a season to mark it watched or unwatched; a button marks the whole
 * series. Play next in the player starts the episode after the one playing (Settings can make it skip watched ones).
 */
final class Episodes {
    private Episodes() { }

    static void show(MainActivity a, String sid, String title) {
        a.seriesTitle = title;
        a.playerReturn = () -> show(a, sid, title);
        a.mode = "episodes";
        a.shell("SERIES  •  " + title);
        final int generation = a.screenGeneration;

        LinearLayout left = column(a, 1), right = column(a, 2);
        a.button("← Back", left, () -> back(a));
        TextView cont = a.button("Loading episodes…", left, () -> { });
        cont.setTypeface(null, Typeface.BOLD);
        cont.requestFocus();

        a.request("get_series_info&series_id=" + a.enc(sid), o -> {
            if (generation != a.screenGeneration) return;
            JSONObject episodes = o.optJSONObject("episodes");
            List<String> seasons = Watch.seasons(episodes);
            if (seasons.isEmpty()) { cont.setText("No episodes yet"); return; }
            JSONObject state = Watch.refresh(a, sid, episodes);
            String[] next = Watch.next(episodes, state);
            if (next != null) {
                int idx = Integer.parseInt(next[1]);
                JSONObject e = episodes.optJSONArray(next[0]).optJSONObject(idx);
                long at = Watch.resumeAt(state, e.optString("id"));
                String what = Watch.label(next[0], e, idx);
                cont.setText(next[2].equals("resume") ? "▶  Resume " + what + (at > 0 ? "  •  from " + Watch.clock(at) : "")
                        : next[2].equals("next") ? "▶  Next: " + what : "▶  Start " + what);
                cont.setOnClickListener(v -> play(a, sid, next[0], e, idx, episodes));
            } else {
                cont.setText("✓  All watched");
                cont.setOnClickListener(v -> Toast.makeText(a, "You've watched every episode. Pick a season to watch again.", Toast.LENGTH_SHORT).show());
            }
            boolean all = state.optBoolean("complete");
            a.button(all ? "↺  Mark series not watched" : "✓  Mark series watched", left, () -> {
                Watch.markAll(a, sid, episodes, null, !all);
                Toast.makeText(a, all ? "Series marked as not watched" : "Every episode marked as watched", Toast.LENGTH_SHORT).show();
                show(a, sid, title);
            });
            TextView[] seasonRows = new TextView[seasons.size()];
            for (int i = 0; i < seasons.size(); i++) {
                String s = seasons.get(i);
                seasonRows[i] = a.button("", left, () -> showSeason(a, right, sid, title, s, episodes, true));
                seasonRows[i].setText(seasonLabel(s, episodes.optJSONArray(s), state));
                seasonRows[i].setOnLongClickListener(v -> {
                    JSONArray eps = episodes.optJSONArray(s);
                    boolean nowDone = Watch.doneIn(eps, Watch.load(a, sid)) < (eps == null ? 0 : eps.length());
                    Watch.markAll(a, sid, episodes, s, nowDone);
                    Toast.makeText(a, "Season " + s + (nowDone ? " marked as watched" : " marked as not watched"), Toast.LENGTH_SHORT).show();
                    a.resumeStreamId = null;
                    show(a, sid, title);
                    return true;
                });
            }
            // Open on the season of the episode just played, else the one with the next episode.
            String resume = a.resumeStreamId, open = next != null ? next[0] : seasons.get(0);
            if (resume != null) for (String s : seasons) {
                JSONArray eps = episodes.optJSONArray(s);
                for (int i = 0; eps != null && i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e != null && resume.equals(e.optString("id"))) open = s;
                }
            }
            showSeason(a, right, sid, title, open, episodes, false);
            if (!a.focusResume(right)) cont.requestFocus();
        }, "Unable to load episodes");
    }

    /** Back from the episode list: to the series details page when it was opened from there, else Series. */
    static void back(MainActivity a) {
        Runnable r = a.episodesBack;
        if (r != null) r.run(); else a.browse("series");
    }

    private static String seasonLabel(String season, JSONArray eps, JSONObject state) {
        int n = eps == null ? 0 : eps.length(), done = Watch.doneIn(eps, state);
        return "Season " + season + "      " + (done == 0 ? n + (n == 1 ? " episode" : " episodes") : done == n ? "✓ watched" : done + "/" + n + " watched");
    }

    private static void showSeason(MainActivity a, LinearLayout right, String sid, String title, String season, JSONObject episodes, boolean focus) {
        right.removeAllViews();
        JSONObject state = Watch.load(a, sid);
        JSONArray eps = episodes.optJSONArray(season);
        TextView head = a.label("Season " + season, 22);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(Theme.ACCENT);
        head.setPadding(a.dp(8), 0, a.dp(8), a.dp(4));
        right.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView first = null;
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) continue;
            final int idx = i;
            String eid = e.optString("id");
            TextView row = a.button(episodeLabel(state, season, e, i), right, () -> play(a, sid, season, e, idx, episodes));
            row.setTag(eid);
            row.setTextSize(19);
            row.setOnLongClickListener(v -> {
                boolean nowDone = !Watch.done(Watch.load(a, sid), eid);
                Watch.setDone(a, sid, eid, season, number(e, idx), nowDone);
                Watch.refresh(a, sid, episodes);
                ((TextView) v).setText(episodeLabel(Watch.load(a, sid), season, e, idx));
                Toast.makeText(a, nowDone ? "Marked as watched" : "Marked as not watched", Toast.LENGTH_SHORT).show();
                return true;
            });
            if (first == null) first = row;
        }
        TextView hint = a.label("Hold OK on an episode, or on a season on the left, to mark it watched or not watched.", 13);
        hint.setTextColor(Theme.HINT);
        hint.setGravity(Gravity.START);
        hint.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        right.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        if (focus && first != null) first.requestFocus();
    }

    private static String episodeLabel(JSONObject state, String season, JSONObject e, int i) {
        String eid = e.optString("id"), name = e.optString("title", "Episode " + (i + 1));
        JSONObject w = Watch.entry(state, eid);
        String mark = w == null ? "      " : w.optBoolean("done") ? "✓   " : "◐   ";
        String left = "";
        if (w != null && !w.optBoolean("done") && w.optLong("dur") > 0) left = "   •   " + Watch.clock(w.optLong("dur") - w.optLong("pos")) + " left";
        return mark + Watch.label(season, e, i) + "   " + name + left;
    }

    private static String number(JSONObject e, int i) {
        String n = e.optString("episode_num");
        return n.isEmpty() || n.equals("0") ? String.valueOf(i + 1) : n;
    }

    /** The episode after the one playing, as {season, index}, or null (not an episode, or the last one). */
    static String[] next(MainActivity a) {
        if (a.watching == null || a.seriesEpisodes == null) return null;
        return Watch.after(a.seriesEpisodes, a.watching[1], Watch.skipWatched(a) ? Watch.load(a, a.watching[0]) : null);
    }

    /** "S1 E5  Title" for the episode after the one playing, or null. */
    static String nextLabel(MainActivity a) {
        String[] n = next(a);
        if (n == null) return null;
        int idx = Integer.parseInt(n[1]);
        JSONObject e = a.seriesEpisodes.optJSONArray(n[0]).optJSONObject(idx);
        String name = e.optString("title");
        return Watch.label(n[0], e, idx) + (name.isEmpty() ? "" : "   " + name);
    }

    /** From the player: saves progress on this episode and starts the next one (next season after a season's last). */
    static boolean playNext(MainActivity a) {
        String[] n = next(a);
        if (n == null) return false;
        String sid = a.watching[0];
        JSONObject episodes = a.seriesEpisodes;
        int idx = Integer.parseInt(n[1]);
        JSONObject e = episodes.optJSONArray(n[0]).optJSONObject(idx);
        Runnable back = a.playerReturn;
        a.closePlayer();
        // Back from the next episode still goes to wherever the first one was started from.
        a.mode = "episodes";
        a.playerReturn = back;
        play(a, sid, n[0], e, idx, episodes);
        return true;
    }

    private static void play(MainActivity a, String sid, String season, JSONObject e, int idx, JSONObject episodes) {
        a.seriesEpisodes = episodes;
        String eid = e.optString("id"), ext = e.optString("container_extension", "mp4"), name = e.optString("title", "Episode " + (idx + 1));
        a.pendingWatch = new String[]{sid, eid, season, number(e, idx)};
        a.play("series", eid, ext, name);
    }

    private static LinearLayout column(MainActivity a, int weight) {
        ScrollView sv = new ScrollView(a);
        a.body.addView(sv, new LinearLayout.LayoutParams(0, -1, weight));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(4), 0, a.dp(4), 0);
        sv.addView(col);
        return col;
    }
}
