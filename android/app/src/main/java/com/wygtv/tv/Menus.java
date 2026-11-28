package com.wygtv.tv;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;

/**
 * Playlists, first-time setup, Settings and Recently watched: the setup side of the app,
 * organised the way IPTVnator does it (a playlist manager you start from, then a settings page).
 */
final class Menus {

    static final String[] START_KEYS = {"home", "live", "guide", "last", "favorites", "recent"};
    static final String[] START_LABELS = {"Home", "Live TV", "TV Guide", "Last channel watched (CH+/CH- go through Favorites)", "Favorites", "Recently watched"};
    /** In Live TV only mode the app starts on the guide or straight on the last channel. */
    static final String[] START_KEYS_LIVE = {"guide", "last"};
    static final String[] START_LABELS_LIVE = {"TV Guide", "Last channel watched (CH+/CH- go through Favorites)"};
    static final String[] FORMAT_KEYS = {"auto", "ts", "m3u8"};
    static final String[] FORMAT_LABELS = {"Automatic (provider default)", "MPEG-TS (.ts)", "HLS (.m3u8)"};

    private Menus() { }

    // ------------------------------------------------------------------ first-time setup

    static void setup(MainActivity a) {
        a.mode = "setup";
        a.screenGeneration++;
        FrameLayout scene = new FrameLayout(a);
        scene.setBackground(new android.graphics.drawable.ColorDrawable(0xff111111));
        a.setContentView(scene);
        LinearLayout center = new LinearLayout(a);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(a.dp(80), a.dp(20), a.dp(80), a.dp(20));
        scene.addView(center, new FrameLayout.LayoutParams(-1, -1));
        TextView small = a.label("WELCOME TO WYGTV", 15);
        small.setTextColor(Theme.ACCENT);
        small.setLetterSpacing(.18f);
        small.setGravity(Gravity.CENTER);
        center.addView(small, new LinearLayout.LayoutParams(-1, a.dp(35)));
        TextView heading = a.label("Add your first playlist", 30);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        center.addView(heading, new LinearLayout.LayoutParams(-1, a.dp(56)));
        TextView hint = a.label("Pick how your provider gave you access, or restore a WygTV backup. You can add more playlists later from the menu.", 16);
        hint.setTextColor(Theme.MUTED);
        hint.setGravity(Gravity.CENTER);
        center.addView(hint, new LinearLayout.LayoutParams(-1, a.dp(40)));
        LinearLayout tiles = new LinearLayout(a);
        tiles.setGravity(Gravity.CENTER);
        center.addView(tiles, new LinearLayout.LayoutParams(-1, a.dp(170)));
        TextView xtream = a.homeTile("⚙", "Xtream Codes\nserver + username", tiles, () -> xtreamForm(a, null, null));
        a.homeTile("☰", "M3U link\nplaylist URL", tiles, () -> m3uForm(a, null));
        a.homeTile("📡", "HDHomeRun\nantenna TV tuner", tiles, () -> hdhrForm(a, null));
        if (!Brand.OTA) a.homeTile("📡", "Tablo\nantenna TV DVR", tiles, () -> tabloMenu(a, () -> setup(a)));
        a.homeTile("↺", "Restore\nfrom a backup", tiles, () -> Backup.restore(a));
        for (int i = 0; i < tiles.getChildCount(); i++) ((TextView) tiles.getChildAt(i)).setTextSize(21);
        xtream.requestFocus();
    }

    // ------------------------------------------------------------------ playlists manager

    static void playlists(MainActivity a) {
        boolean fromSettings = playlistsFromSettings;
        a.mode = "playlists";
        a.shell(fromSettings ? "SETTINGS  •  PLAYLISTS" : "PLAYLISTS");
        // Opened from Settings > Manage playlists: the Settings tree stays collapsed on the left, Manage playlists marked.
        if (fromSettings) settingsTree(a, GROUP_PLAYLISTS);
        LinearLayout list = column(a);
        note(a, list, "Every playlist that is on is ready to watch at once in Live TV, Movies, Series, the TV guide and Search. Press OK on a playlist to turn it on or off, edit it, refresh it or delete it.");
        JSONArray all = a.library.all();
        View focus = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p == null) continue;
            boolean on = a.library.isOn(p);
            String updated = on ? AutoRefresh.playlistLine(a, p) : "";
            TextView row = a.button((on ? "☑  " : "☐  ") + p.optString("title", "Playlist") + (on ? "" : "   (off)") + "\n      " + describe(p) + (updated.isEmpty() ? "" : "  •  " + updated), list, () -> playlistActions(a, p));
            row.setTextSize(19);
            if (!on) row.setTextColor(Theme.MUTED);
            if (focus == null) focus = row;
        }
        if (all.length() > 1) a.button("☑  Turn playlists on or off…", list, () -> Manage.playlistsOnOff(a, () -> playlists(a)));
        TextView addX = a.button("+  Add an Xtream Codes login", list, () -> xtreamForm(a, null, null));
        a.button("+  Add an M3U playlist link", list, () -> m3uForm(a, null));
        int extraCount = a.library.extras.urls().size();
        a.button("▦  TV guides (EPG)" + "\n      " + (extraCount == 0 ? "extra XMLTV guide links, free guides by country, which guide wins" : extraCount + (extraCount == 1 ? " extra guide link" : " extra guide links")), list, () -> extraGuides(a, () -> playlists(a)));
        a.button("⟳  Update playlists and guides now\n      " + AutoRefresh.summary(a), list, () -> {
            if (AutoRefresh.running()) { Toast.makeText(a, "Already updating. The status line at the bottom shows how it's going.", Toast.LENGTH_LONG).show(); return; }
            boolean wait = AutoRefresh.waits(a.prefs);
            AutoRefresh.updateAll(a, wait ? () -> playlists(a) : null);
            if (!wait) playlists(a);
        });
        (focus == null ? addX : focus).requestFocus();
    }

    static String describe(JSONObject p) {
        StringBuilder s = new StringBuilder();
        if (Library.isTablo(p)) {
            s.append(p.optString("model").isEmpty() ? "Tablo" : p.optString("model"));
            int n = p.optInt("count", -1);
            if (n >= 0) s.append("  •  ").append(n).append(n == 1 ? " channel" : " channels");
            s.append("  •  ").append(hostOf(p.optString("url")));
            int t = p.optInt("tuners", 0);
            if (t > 0) s.append("  •  ").append(t).append(t == 1 ? " tuner" : " tuners");
        } else if (Library.isHdhr(p)) {
            s.append("HDHomeRun tuner");
            int n = p.optInt("count", -1);
            if (n >= 0) s.append("  •  ").append(n).append(n == 1 ? " channel" : " channels");
            s.append("  •  ").append(hostOf(p.optString("url")));
            int t = p.optInt("tuners", 0);
            if (t > 0) s.append("  •  ").append(t).append(t == 1 ? " tuner" : " tuners");
        } else if (Library.M3U.equals(p.optString("type"))) {
            s.append("M3U playlist");
            int n = p.optInt("count", -1);
            if (n >= 0) s.append("  •  ").append(n).append(n == 1 ? " channel" : " channels");
            s.append("  •  ").append(hostOf(p.optString("url")));
        } else {
            s.append("Xtream Codes  •  ").append(hostOf(p.optString("host"))).append("  •  ").append(p.optString("user"));
            String exp = expiryText(p.optString("expiry"));
            if (!exp.isEmpty()) s.append("  •  ").append(exp);
        }
        if (!p.optString("epg").isEmpty()) s.append("  •  own TV guide");
        return s.toString();
    }

    static String hostOf(String url) {
        try { String h = new URI(url).getHost(); return h == null ? url : h; } catch (Exception e) { return url; }
    }

    /** Xtream gives a unix time (or null/"Unknown"); show it as a date. */
    static String expiryText(String exp) {
        if (exp == null || exp.isEmpty() || exp.equals("null") || exp.equalsIgnoreCase("unknown")) return "";
        try {
            long t = Long.parseLong(exp.trim()) * 1000L;
            return "expires " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(t));
        } catch (NumberFormatException e) {
            return "expires " + exp;
        }
    }

    private static void playlistActions(MainActivity a, JSONObject p) {
        boolean on = a.library.isOn(p);
        String[] items = {on ? "Turn off (skip it in menus, guides, Search and updates)" : "Turn on (show it everywhere again)", "Edit", "Refresh now", "Manage categories (hide or show)", "TV guide (EPG) link" + (p.optString("epg").isEmpty() ? "" : "  •  " + hostOf(p.optString("epg"))), "Delete"};
        new Dialogs.Builder(a).setTitle(p.optString("title", "Playlist") + (on ? "" : "   (off)")).setItems(items, (d, which) -> {
            if (which == 0) setOn(a, p, !on);
            else if (which == 1) { if (Library.isTablo(p)) tabloForm(a, p); else if (Library.isHdhr(p)) hdhrForm(a, p); else if (Library.M3U.equals(p.optString("type"))) m3uForm(a, p); else xtreamForm(a, p, null); }
            else if (which == 2) refresh(a, p);
            else if (which == 3) Manage.categories(a, p, () -> playlists(a));
            else if (which == 4) guideSource(a, p, () -> playlists(a));
            else new Dialogs.Builder(a).setTitle("Delete “" + p.optString("title", "Playlist") + "”?")
                        .setMessage("Its favorites stay saved, so they come back if you add the same playlist again.")
                        .setPositiveButton("Delete", (d2, w2) -> {
                            a.library.remove(p.optString("id"));
                            a.forgetPlaylistData();
                            if (a.library.isEmpty()) setup(a); else playlists(a);
                        }).setNegativeButton("Cancel", null).show();
        }).show();
    }

    /** Turns one playlist on or off (at least one stays on), then redraws the playlists screen. */
    private static void setOn(MainActivity a, JSONObject p, boolean on) {
        java.util.Set<String> off = new java.util.HashSet<>(a.library.offIds());
        if (on) off.remove(p.optString("id")); else off.add(p.optString("id"));
        if (!on && off.size() >= a.library.all().length()) { Toast.makeText(a, "Keep at least one playlist on.", Toast.LENGTH_LONG).show(); return; }
        a.prefs.edit().putStringSet(Library.OFF, off).apply();
        Manage.applyPlaylistsOnOff(a);
        Toast.makeText(a, p.optString("title", "Playlist") + (on ? " is on." : " is off. It is skipped until you turn it back on."), Toast.LENGTH_LONG).show();
        playlists(a);
    }

    /** Refresh now: the playlist's channels (or account) and its guide, shown in the status line or on the progress screen. */
    private static void refresh(MainActivity a, JSONObject p) {
        boolean tuner = Library.isHdhr(p);
        AutoRefresh.updateOne(a, p, () -> { if (tuner) backToTuners(a); else playlists(a); });
    }

    // ------------------------------------------------------------------ add / edit forms

    /** Xtream login form. {@code prefill} = {server, user, pass} from a pasted get.php link. */
    static void xtreamForm(MainActivity a, JSONObject existing, String[] prefill) {
        a.mode = "login";
        a.shell(existing == null ? "ADD XTREAM CODES LOGIN" : "EDIT PLAYLIST");
        LinearLayout form = column(a);
        form.setPadding(a.dp(80), a.dp(4), a.dp(80), 0);
        EditText title = a.field(form, "Playlist name (e.g. Living room)", existing == null ? "" : existing.optString("title"));
        EditText host = a.field(form, "Server URL (http://host:port) or a full get.php link", prefill != null ? prefill[0] : existing == null ? "" : existing.optString("host"));
        EditText user = a.field(form, "Username", prefill != null ? prefill[1] : existing == null ? "" : existing.optString("user"));
        EditText pass = a.field(form, "Password", prefill != null ? prefill[2] : existing == null ? "" : existing.optString("pass"));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText epg = a.field(form, "TV guide (XMLTV) link, optional: leave empty to use the provider's", existing == null ? "" : existing.optString("epg"));
        a.button("Save & connect", form, () -> {
            String h = host.getText().toString().trim();
            String[] fromLink = M3u.xtreamLogin(h);
            if (fromLink != null) { h = fromLink[0]; user.setText(fromLink[1]); pass.setText(fromLink[2]); host.setText(h); }
            h = h.replaceAll("/+$", "");
            String u = user.getText().toString().trim(), pw = pass.getText().toString();
            if (!XtreamProtocol.validLogin(h, u, pw)) { alert(a, "Enter an http:// or https:// server address, a username and a password."); return; }
            final String server = h;
            a.fetch(h + "/player_api.php?username=" + a.enc(u) + "&password=" + a.enc(pw), data -> {
                JSONObject info = data.optJSONObject("user_info");
                if (info == null || info.optInt("auth", 0) != 1 || !"Active".equalsIgnoreCase(info.optString("status"))) {
                    alert(a, "Login rejected or account inactive. Nothing was saved.");
                    return;
                }
                JSONObject p = existing != null ? existing : Library.newPlaylist(Library.XTREAM);
                String name = title.getText().toString().trim();
                try {
                    p.put("title", name.isEmpty() ? hostOf(server) : name).put("host", server).put("user", u).put("pass", pw)
                            .put("expiry", info.optString("exp_date", "")).put("epg", epg.getText().toString().trim());
                } catch (JSONException ignored) { }
                a.library.put(p);
                a.switchPlaylist(p);
                Toast.makeText(a, "Connected to " + p.optString("title"), Toast.LENGTH_SHORT).show();
                AutoRefresh.added(a, p, a::firstScreen);
            }, "Connection failed");
        });
        a.button("Cancel", form, () -> a.leaveForm());
        (existing == null ? title : host).requestFocus();
    }

    static void m3uForm(MainActivity a, JSONObject existing) {
        a.mode = "login";
        a.shell(existing == null ? "ADD M3U PLAYLIST" : "EDIT PLAYLIST");
        LinearLayout form = column(a);
        form.setPadding(a.dp(80), a.dp(4), a.dp(80), 0);
        EditText title = a.field(form, "Playlist name (e.g. Free channels)", existing == null ? "" : existing.optString("title"));
        EditText url = a.field(form, "M3U / M3U8 link (http://…)", existing == null ? "" : existing.optString("url"));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText epg = a.field(form, "TV guide (XMLTV) link, optional: leave empty to use the playlist's own", existing == null ? "" : existing.optString("epg"));
        note(a, form, "M3U playlists give you Live TV, grouped the way the playlist groups them.");
        a.button("Save & load", form, () -> {
            String link = url.getText().toString().trim();
            if (!(link.startsWith("http://") || link.startsWith("https://"))) { alert(a, "Enter a link that starts with http:// or https://."); return; }
            String[] xt = M3u.xtreamLogin(link);
            if (xt != null && existing == null) {
                new Dialogs.Builder(a).setTitle("This is an Xtream Codes link")
                        .setMessage("Adding it as Xtream Codes also gives you Movies, Series and the provider's TV guide.")
                        .setPositiveButton("Add as Xtream", (d, w) -> xtreamForm(a, null, xt))
                        .setNegativeButton("Keep as M3U", (d, w) -> loadM3u(a, existing, title.getText().toString().trim(), link, epg.getText().toString().trim()))
                        .show();
                return;
            }
            loadM3u(a, existing, title.getText().toString().trim(), link, epg.getText().toString().trim());
        });
        a.button("Cancel", form, () -> a.leaveForm());
        (existing == null ? title : url).requestFocus();
    }

    private static void loadM3u(MainActivity a, JSONObject existing, String name, String link, String epg) {
        a.status.setText("Downloading playlist…");
        Toast.makeText(a, "Downloading playlist…", Toast.LENGTH_SHORT).show();
        final int gen = a.screenGeneration;
        new Thread(() -> {
            M3u.Playlist m = null;
            try { m = a.library.downloadM3u(link); } catch (Exception ignored) { }
            final M3u.Playlist loaded = m;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || gen != a.screenGeneration) return;
                if (loaded == null || loaded.channels.isEmpty()) {
                    a.status.setText("");
                    alert(a, loaded == null ? "Couldn't download a playlist from that link. Check it and your network." : "That playlist has no channels.");
                    return;
                }
                JSONObject p = existing != null ? existing : Library.newPlaylist(Library.M3U);
                try {
                    p.put("title", name.isEmpty() ? hostOf(link) : name).put("url", link).put("epg", epg).put("count", loaded.channels.size());
                } catch (JSONException ignored) { }
                a.library.put(p);
                a.switchPlaylist(p);
                Toast.makeText(a, loaded.channels.size() + " channels in " + loaded.groups.size() + " groups", Toast.LENGTH_LONG).show();
                AutoRefresh.added(a, p, a::firstScreen);
            });
        }).start();
    }

    // ------------------------------------------------------------------ HDHomeRun tuners

    /**
     * Add or edit an HDHomeRun tuner. The address starts as hdhomerun.local (the name every tuner answers to
     * on a home network); "Find tuners" searches the network, and any IP address can be typed instead.
     */
    static void hdhrForm(MainActivity a, JSONObject existing) {
        a.mode = "hdhr";
        a.shell(existing == null ? "ADD HDHOMERUN TUNER" : "EDIT TUNER");
        LinearLayout form = column(a);
        form.setPadding(a.dp(80), a.dp(4), a.dp(80), 0);
        EditText title = a.field(form, "Name (shows in Live TV)", existing == null ? Antenna.DEFAULT_NAME : Antenna.nameOf(existing));
        String addr = existing == null ? Hdhr.DEFAULT_HOST : hostAndPort(Hdhr.baseOfLineup(existing.optString("url")));
        EditText host = a.field(form, "Tuner address: hdhomerun.local or its IP, e.g. 192.168.1.50", addr);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText epg = a.field(form, "TV guide (XMLTV) link, optional: leave empty to use the HDHomeRun guide", existing == null ? "" : existing.optString("epg"));
        note(a, form, "Channels come from the tuner's lineup (the same list as http://hdhomerun.local/lineup.html), grouped by channel number. "
                + "The TV guide comes from SiliconDust's free HDHomeRun guide. If hdhomerun.local isn't found, press Find tuners or type the tuner's IP address "
                + "(shown in your router, or at my.hdhomerun.com).");
        TextView connect = a.button("Save & load channels", form, () -> connectTuner(a, existing, title.getText().toString().trim(), host.getText().toString(), epg.getText().toString().trim()));
        a.button("⌕  Find tuners on my network", form, () -> findTuners(a, existing, title, host, epg));
        if (existing == null) a.button("📡  I have a Tablo instead", form, () -> tabloMenu(a, () -> { if (a.library.isEmpty()) hdhrForm(a, null); else backToTuners(a); }));
        a.button("Cancel", form, () -> { if (!a.library.isEmpty()) backToTuners(a); else if (Brand.OTA) a.finish(); else setup(a); });
        (existing == null ? connect : host).requestFocus();
    }

    /** Settings > HDHomeRun antenna TV once a tuner is added: pick a tuner, or add another. */
    private static void tunerList(MainActivity a) {
        java.util.List<JSONObject> tuners = hdhrTuners(a);
        String[] names = new String[tuners.size() + 1];
        for (int i = 0; i < tuners.size(); i++) names[i] = Antenna.nameOf(tuners.get(i)) + (a.library.isOn(tuners.get(i)) ? "" : "  (off)") + "  •  " + describe(tuners.get(i));
        names[tuners.size()] = "+  Add another tuner";
        new Dialogs.Builder(a).setTitle("HDHomeRun antenna TV").setItems(names, (d, w) -> {
            if (w == tuners.size()) hdhrForm(a, null); else tunerActions(a, tuners.get(w));
        }).setNegativeButton("Close", null).show();
    }

    private static void tunerActions(MainActivity a, JSONObject t) {
        new Dialogs.Builder(a).setTitle(Antenna.nameOf(t)).setItems(new String[]{"Edit name or address", "Refresh channels (after a channel scan)", "Remove"}, (d, w) -> {
            if (w == 0) { if (Library.isTablo(t)) tabloForm(a, t); else hdhrForm(a, t); }
            else if (w == 1) refresh(a, t);
            else new Dialogs.Builder(a).setTitle("Remove “" + Antenna.nameOf(t) + "”?")
                        .setMessage(Antenna.nameOf(t) + " disappears from Live TV. Its favorites stay saved, so they come back if you add the same tuner again.")
                        .setPositiveButton("Remove", (d2, w2) -> {
                            a.library.remove(t.optString("id"));
                            a.forgetPlaylistData();
                            if (a.library.isEmpty()) setup(a); else backToTuners(a);
                        }).setNegativeButton("Cancel", null).show();
        }).show();
    }

    /** "http://192.168.1.50:80" → "192.168.1.50", "http://x:5004" → "x:5004". */
    static String hostAndPort(String base) {
        try {
            URI u = new URI(base);
            if (u.getHost() == null) return base;
            return u.getPort() < 0 || u.getPort() == 80 ? u.getHost() : u.getHost() + ":" + u.getPort();
        } catch (Exception e) {
            return base;
        }
    }

    private static void findTuners(MainActivity a, JSONObject existing, EditText title, EditText host, EditText epg) {
        a.status.setText("Looking for HDHomeRun tuners…");
        Toast.makeText(a, "Looking for HDHomeRun tuners…", Toast.LENGTH_SHORT).show();
        final int gen = a.screenGeneration;
        new Thread(() -> {
            java.util.List<Hdhr.Device> found = Hdhr.discoverAll();
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || gen != a.screenGeneration) return;
                a.status.setText("");
                if (found.isEmpty()) {
                    alert(a, "No HDHomeRun tuner answered. Check that it's powered on and on the same network as this device, then type its IP address instead.");
                    return;
                }
                String[] names = new String[found.size()];
                for (int i = 0; i < names.length; i++) names[i] = found.get(i).summary();
                new Dialogs.Builder(a).setTitle(found.size() == 1 ? "Found 1 tuner" : "Found " + found.size() + " tuners").setItems(names, (d, w) -> {
                    Hdhr.Device dev = found.get(w);
                    host.setText(hostAndPort(dev.base));
                    if (title.getText().toString().trim().isEmpty() && !dev.name.isEmpty()) title.setText(dev.name);
                    connectTuner(a, existing, title.getText().toString().trim(), hostAndPort(dev.base), epg.getText().toString().trim());
                }).setNegativeButton("Cancel", null).show();
            });
        }).start();
    }

    /** Reads the tuner's discover.json and lineup, then saves it as a playlist and opens Home. */
    private static void connectTuner(MainActivity a, JSONObject existing, String name, String address, String epg) {
        String base = Hdhr.baseFromInput(address);
        if (base.isEmpty()) { alert(a, "Enter the tuner's address, like hdhomerun.local or 192.168.1.50."); return; }
        a.status.setText("Connecting to the tuner…");
        Toast.makeText(a, "Connecting to " + hostAndPort(base) + "…", Toast.LENGTH_SHORT).show();
        final int gen = a.screenGeneration;
        new Thread(() -> {
            Hdhr.Device dev = null;
            M3u.Playlist m = null;
            String problem = null;
            try { dev = Hdhr.describe(base); } catch (Exception e) {
                problem = "No HDHomeRun tuner answered at " + hostAndPort(base) + ". Check the address, or press Find tuners."
                        + (base.contains(".local") ? " Some devices can't look up hdhomerun.local: use the tuner's IP address instead." : "");
            }
            if (dev != null) {
                try { m = a.library.downloadM3u(dev.lineupUrl); } catch (Exception e) { problem = "The tuner answered but its channel list couldn't be read."; }
                if (m != null && m.channels.isEmpty()) problem = "The tuner has no channels yet. Run a channel scan in the HDHomeRun app or at " + hostAndPort(dev.base) + ", then try again.";
            }
            final Hdhr.Device device = dev;
            final M3u.Playlist loaded = m;
            final String error = problem;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || gen != a.screenGeneration) return;
                a.status.setText("");
                if (error != null) { alert(a, error); return; }
                JSONObject p = existing != null ? existing : Library.newPlaylist(Library.M3U);
                if (existing != null && !existing.optString("url").equals(device.lineupUrl)) {
                    // A new address gives new channel links: drop the old cached copy.
                    a.library.m3uFile(existing.optString("url")).delete();
                }
                try {
                    p.put("title", name.isEmpty() ? Antenna.DEFAULT_NAME : name)
                            .put("url", device.lineupUrl).put("epg", epg).put("count", loaded.channels.size())
                            .put("hdhr", true).put("device_id", device.id).put("model", device.model).put("tuners", device.tuners);
                } catch (JSONException ignored) { }
                boolean first = a.library.isEmpty();
                a.library.put(p);
                if (first || p.optString("id").equals(a.library.activeId())) a.switchPlaylist(p);
                Toast.makeText(a, loaded.channels.size() + " antenna channels added. Find them in Live TV under " + p.optString("title") + ".", Toast.LENGTH_LONG).show();
                if (first) a.firstScreen(); else backToTuners(a);
            });
        }).start();
    }

    /** Saved HDHomeRun tuners (Tablos have their own settings row). */
    private static java.util.List<JSONObject> hdhrTuners(MainActivity a) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (JSONObject t : Antenna.allTuners(a)) if (!Library.isTablo(t)) out.add(t);
        return out;
    }

    // ------------------------------------------------------------------ Tablo

    private static java.util.List<JSONObject> tablos(MainActivity a) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (JSONObject t : Antenna.allTuners(a)) if (Library.isTablo(t)) out.add(t);
        return out;
    }

    /** Settings line for Tablo: legacy Tablos added, and whether the 4th Gen (Tablo app) button is on. */
    static String tabloSummary(MainActivity a) {
        java.util.List<JSONObject> t = tablos(a);
        String legacy = t.isEmpty() ? "" : (t.size() == 1 ? describe(t.get(0)) : t.size() + " Tablos") + "  •  ";
        String app = TabloApp.isOn(a.prefs) ? "📡 " + TabloApp.nameOf(a.prefs) + " opens the Tablo app  •  " : "";
        if (legacy.isEmpty() && app.isEmpty()) return "not added  •  press OK to add a Tablo (black models play here; the white 4th Gen opens the Tablo app)";
        return legacy + app + "press OK to add, edit or remove";
    }

    /**
     * Tablo: which kind you have decides how it works. The black Tablos (2013–2023) share their channels on the
     * home network, so they play right here like an HDHomeRun. The white 4th Gen only talks to Tablo's own app, so
     * it gets a button that opens that app. {@code back} redraws the screen this was opened from.
     */
    static void tabloMenu(MainActivity a, Runnable back) {
        java.util.List<JSONObject> have = tablos(a);
        String[] items = new String[have.size() + 3];
        for (int i = 0; i < have.size(); i++) items[i] = "📡 " + Antenna.nameOf(have.get(i)) + (a.library.isOn(have.get(i)) ? "" : "  (off)") + "  •  " + describe(have.get(i));
        items[have.size()] = "+  Add a black Tablo (DUAL, QUAD, LITE: 2013–2023)  •  plays in " + Brand.NAME;
        items[have.size() + 1] = "📡  White 4th Gen Tablo  •  " + TabloApp.summary(a);
        items[have.size() + 2] = "Close";
        new Dialogs.Builder(a).setTitle("Tablo antenna TV").setItems(items, (d, w) -> {
            if (w < have.size()) tunerActions(a, have.get(w));
            else if (w == have.size()) tabloForm(a, null);
            else if (w == have.size() + 1) TabloApp.settings(a, back);
        }).show();
    }

    /** Add or edit a legacy (black) Tablo: its IP address, or "Find my Tablo" through Tablo's cloud lookup. */
    static void tabloForm(MainActivity a, JSONObject existing) {
        a.mode = "hdhr";
        a.shell(existing == null ? "ADD TABLO" : "EDIT TABLO");
        LinearLayout form = column(a);
        form.setPadding(a.dp(80), a.dp(4), a.dp(80), 0);
        EditText title = a.field(form, "Name (shows in Live TV)", existing == null ? Tablo.DEFAULT_NAME : Antenna.nameOf(existing));
        String addr = existing == null ? "" : hostOf(existing.optString("url"));
        EditText host = a.field(form, "Tablo's IP address, e.g. 192.168.1.60", addr);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText epg = a.field(form, "TV guide (XMLTV) link, optional: leave empty to use the Tablo's own guide", existing == null ? "" : existing.optString("epg"));
        note(a, form, "For the black Tablo models (DUAL, QUAD, LITE, 2013–2023). Channels come from the Tablo's last channel scan and play right here, "
                + "and the TV guide comes from the Tablo's own guide (an XMLTV link, if you add one, is used first); "
                + "each one uses one of the Tablo's tuners while it plays. Find my Tablo asks Tablo's servers which Tablo is on this network, "
                + "or type its IP address (shown in your router, or in the Tablo app's settings). "
                + "For the white 4th Gen Tablo, go back and pick \"White 4th Gen Tablo\" instead: it only plays in the Tablo app.");
        TextView connect = a.button("Save & load channels", form, () -> connectTablo(a, existing, title.getText().toString().trim(), host.getText().toString(), epg.getText().toString().trim()));
        a.button("⌕  Find my Tablo", form, () -> findTablos(a, existing, title, host, epg));
        a.button("Cancel", form, () -> { if (!a.library.isEmpty()) backToTuners(a); else a.setup(); });
        (existing == null ? host : connect).requestFocus();
    }

    private static void findTablos(MainActivity a, JSONObject existing, EditText title, EditText host, EditText epg) {
        a.status.setText("Looking for your Tablo…");
        Toast.makeText(a, "Looking for your Tablo…", Toast.LENGTH_SHORT).show();
        final int gen = a.screenGeneration;
        new Thread(() -> {
            java.util.List<Tablo.Device> found = Tablo.discoverAll();
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || gen != a.screenGeneration) return;
                a.status.setText("");
                if (found.isEmpty()) {
                    alert(a, "No black Tablo was found on this network. Check it's on and connected, then type its IP address instead. "
                            + "A white 4th Gen Tablo isn't found here: it only plays in the Tablo app (see White 4th Gen Tablo in the Tablo settings).");
                    return;
                }
                String[] names = new String[found.size()];
                for (int i = 0; i < names.length; i++) names[i] = found.get(i).summary();
                new Dialogs.Builder(a).setTitle(found.size() == 1 ? "Found 1 Tablo" : "Found " + found.size() + " Tablos").setItems(names, (d, w) -> {
                    Tablo.Device dev = found.get(w);
                    host.setText(hostOf(dev.base));
                    connectTablo(a, existing, title.getText().toString().trim(), hostOf(dev.base), epg.getText().toString().trim());
                }).setNegativeButton("Cancel", null).show();
            });
        }).start();
    }

    /** Reads the Tablo's /server/info and channels, then saves it like a tuner. */
    private static void connectTablo(MainActivity a, JSONObject existing, String name, String address, String epg) {
        String base = Tablo.baseFromInput(address);
        if (base.isEmpty()) { alert(a, "Enter the Tablo's IP address, like 192.168.1.60, or press Find my Tablo."); return; }
        a.status.setText("Connecting to the Tablo…");
        Toast.makeText(a, "Connecting to " + hostOf(base) + "…", Toast.LENGTH_SHORT).show();
        final int gen = a.screenGeneration;
        new Thread(() -> {
            Tablo.Device dev = null;
            M3u.Playlist m = null;
            String problem = null;
            boolean locked = false;
            String link = Tablo.channelsUrl(base);
            try { dev = Tablo.describe(base); } catch (Tablo.LockedException e) { locked = true; } catch (Exception e) {
                problem = "No black Tablo answered at " + hostOf(base) + ". Check the address and that this device is on the same network. "
                        + "A white 4th Gen Tablo can't be added here: turn on White 4th Gen Tablo in the Tablo settings instead.";
            }
            if (dev != null) {
                try { m = a.library.downloadM3u(link); }
                catch (Exception e) {
                    if (e instanceof Tablo.LockedException) locked = true;
                    else problem = "The Tablo answered but its channel list couldn't be read.";
                }
                if (m != null && m.channels.isEmpty()) problem = "The Tablo has no channels yet. Run a channel scan in the Tablo app, then try again.";
            }
            final Tablo.Device device = dev;
            final M3u.Playlist loaded = m;
            final String error = problem;
            final boolean refused = locked;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || gen != a.screenGeneration) return;
                a.status.setText("");
                if (refused) {
                    new Dialogs.Builder(a).setTitle("This Tablo won't share its channels").setMessage(Tablo.LOCKED)
                            .setPositiveButton("Use the Tablo app", (d, w) -> TabloApp.settings(a, () -> { if (!a.library.isEmpty()) backToTuners(a); else a.setup(); }))
                            .setNegativeButton("Close", null).show();
                    return;
                }
                if (error != null) { alert(a, error); return; }
                JSONObject p = existing != null ? existing : Library.newPlaylist(Library.M3U);
                if (existing != null && !existing.optString("url").equals(link)) a.library.m3uFile(existing.optString("url")).delete();
                try {
                    p.put("title", name.isEmpty() ? Tablo.DEFAULT_NAME : name)
                            .put("url", link).put("epg", epg).put("count", loaded.channels.size())
                            .put("tablo", true).put("device_id", device.id).put("model", device.model).put("tuners", device.tuners);
                } catch (JSONException ignored) { }
                boolean first = a.library.isEmpty();
                a.library.put(p);
                if (first || p.optString("id").equals(a.library.activeId())) a.switchPlaylist(p);
                Toast.makeText(a, loaded.channels.size() + " Tablo channels added. Find them in Live TV under " + p.optString("title") + ".", Toast.LENGTH_LONG).show();
                if (first) a.firstScreen(); else backToTuners(a);
            });
        }).start();
    }

    // ------------------------------------------------------------------ settings

    /** Settings is four groups; each opens its own page. "" = the top page. */
    static final String GROUP_PLAYLISTS = "playlists", GROUP_PLAYER = "player", GROUP_APPEARANCE = "appearance", GROUP_BACKUP = "backup";
    static final String[] GROUPS = {GROUP_PLAYLISTS, GROUP_PLAYER, GROUP_APPEARANCE, GROUP_BACKUP};
    static String settingsGroup = "";

    /** Opens the top Settings page (from Home, the side menu, or Back out of a group). */
    static void settings(MainActivity a) { settingsGroup = ""; settings(a, 0); }

    /** Back on a Settings group page goes up to the top Settings page, on that group. */
    static boolean settingsBack(MainActivity a) {
        if (settingsGroup.isEmpty()) return false;
        int at = indexOf(GROUPS, settingsGroup);
        settingsGroup = "";
        settings(a, at);
        return true;
    }

    /** Opens one Settings group page. */
    static void settingsGroup(MainActivity a, String group) { settingsGroup = group; settings(a, 0); }

    /** Draws the current Settings page (top or a group) and focuses row {@code focusIndex}. */
    static void settings(MainActivity a, int focusIndex) {
        a.mode = "settings";
        String g = settingsGroup;
        a.shell(g.isEmpty() ? "SETTINGS" : "SETTINGS  •  " + groupTitle(g).toUpperCase(java.util.Locale.ROOT));
        // Inside a group, the Settings groups stay on the left as a narrow column (the menu tree, collapsed upward).
        if (!g.isEmpty()) settingsTree(a, g);
        LinearLayout list = column(a);
        boolean m3u = a.library.activeIsM3u();
        JSONObject active = a.library.find(a.library.activeId());
        boolean liveOnly = a.liveOnly();
        if (g.isEmpty()) {
            if (!Brand.OTA) row(a, list, groupIcon(GROUP_PLAYLISTS) + "  " + groupTitle(GROUP_PLAYLISTS), "playlists, HDHomeRun, AirTV, Tablo, manage categories, hide and show channels, channel groups, TV guides (EPG)", () -> settingsGroup(a, GROUP_PLAYLISTS));
            if (Brand.OTA) row(a, list, groupIcon(GROUP_PLAYLISTS) + "  Antenna", "HDHomeRun, AirTV, Tablo, TV guides", () -> settingsGroup(a, GROUP_PLAYLISTS));
            row(a, list, groupIcon(GROUP_PLAYER) + "  " + groupTitle(GROUP_PLAYER), "built-in or external player, live stream format, buffer, video decoder, subtitles, watched series, play next", () -> settingsGroup(a, GROUP_PLAYER));
            row(a, list, groupIcon(GROUP_APPEARANCE) + "  " + groupTitle(GROUP_APPEARANCE), "theme, glass effect, app mode, main screen, start-up screen, side menu order, Exit button, guide rows, channel order, live preview, movie and series info, IMDb ratings", () -> settingsGroup(a, GROUP_APPEARANCE));
            row(a, list, groupIcon(GROUP_BACKUP) + "  " + groupTitle(GROUP_BACKUP), (Brand.STORE ? "back up, restore  •  " : "back up, restore, check for updates  •  ") + Brand.NAME + " " + Updater.installedName(a), () -> settingsGroup(a, GROUP_BACKUP));
        } else if (g.equals(GROUP_PLAYLISTS)) {
            if (!Brand.OTA) row(a, list, "Manage playlists", Manage.playlistSummary(a) + "  •  press OK to add, edit, refresh, delete or turn playlists on and off", () -> { playlistsFromSettings = true; playlists(a); });
            final int onOffRow = list.getChildCount();
            if (!Brand.OTA) row(a, list, "Turn playlists on or off", Manage.playlistSummary(a) + "  •  press OK to tick the playlists to use. Unticked ones are skipped in menus, guides, Search and updates",
                    () -> Manage.playlistsOnOff(a, () -> settings(a, onOffRow)));
            java.util.List<JSONObject> tuners = hdhrTuners(a);
            row(a, list, "HDHomeRun antenna TV", tuners.isEmpty()
                    ? "not added  •  press OK to add your tuner. Its channels show in Live TV as " + Antenna.DEFAULT_NAME
                    : (tuners.size() == 1 ? describe(tuners.get(0)) : tuners.size() + " tuners") + "  •  press OK to add, edit, refresh or remove",
                    () -> { if (tuners.isEmpty()) hdhrForm(a, null); else tunerList(a); });
            final int airRow = list.getChildCount();
            row(a, list, "AirTV antenna TV", AirTv.summary(a), () -> AirTv.settings(a, () -> settings(a, airRow)));
            final int tabloRow = list.getChildCount();
            if (!Brand.OTA) row(a, list, "Tablo antenna TV", tabloSummary(a), () -> tabloMenu(a, () -> settings(a, tabloRow)));
            final int catsRow = list.getChildCount();
            row(a, list, "Manage categories", Manage.categorySummary(a) + "  •  press OK to tick the categories to show, playlist by playlist",
                    () -> Manage.categories(a, () -> settings(a, catsRow)));
            final int groupsRow = list.getChildCount();
            row(a, list, "Channel groups", VirtualCategories.all(a).length() + " in Live TV  •  press OK to make a New Channel Group, or rename or delete one", () ->
                    VirtualCategories.manage(a, () -> settings(a, groupsRow)));
            final int guideRow = list.getChildCount();
            String custom = a.saved("epg_url");
            String guideState = a.library.guideStatus();
            String source = !custom.isEmpty() ? hostOf(custom) + " (your link)" : a.library.activeIsHdhr() ? (Library.isTablo(active) ? "Tablo guide (from the Tablo)" : "HDHomeRun guide (from the tuner)") : m3u ? (a.library.guideUrl().isEmpty() ? "none in this playlist" : hostOf(a.library.guideUrl()) + " (from the playlist)") : "the provider's guide";
            row(a, list, "TV guide source", source + (guideState.isEmpty() ? "" : "  •  " + guideState), () -> guideSource(a, active, () -> settings(a, guideRow)));
            final int reloadRow = list.getChildCount();
            if ((!custom.isEmpty() || m3u) && active != null) row(a, list, "Reload TV guide", "download this playlist and its guide again now", () ->
                    AutoRefresh.updateOne(a, active, () -> settings(a, reloadRow)));
            final int extraRow = list.getChildCount();
            int extraCount = a.library.extras.urls().size();
            row(a, list, "Extra TV guides (XMLTV)", extraCount == 0
                    ? "none  •  press OK to add guide links that fill in channels with no program info"
                    : extraCount + (extraCount == 1 ? " link" : " links") + "  •  " + (a.library.extras.first() ? "used first, the playlist's guide fills the rest" : "fill in channels the playlist's guide leaves empty"),
                    () -> extraGuides(a, () -> settings(a, extraRow)));
            updateRows(a, list);
            final int recentRow = list.getChildCount();
            int recent = a.library.recent().length();
            row(a, list, "Recently watched", recent == 0 ? "empty" : recent + " items  •  press OK to clear", () -> {
                if (a.library.recent().length() == 0) return;
                new Dialogs.Builder(a).setTitle("Clear Recently watched?").setPositiveButton("Clear", (d, w) -> { a.library.clearRecent(); settings(a, recentRow); })
                        .setNegativeButton("Cancel", null).show();
            });
        } else if (g.equals(GROUP_PLAYER)) {
            final int playerRow = list.getChildCount();
            row(a, list, "Player", ExternalPlayer.label(a) + "  •  press OK to keep WygTV's own player or use another video app such as VLC or MX Player", () -> choosePlayer(a, playerRow));
            if (Pip.supported(a)) {
                final int pipRow = list.getChildCount();
                row(a, list, "Picture-in-picture", (Pip.onHome(a.prefs) ? "On" : "Off") + "  •  pressing Home while something plays keeps it playing in a small window. Press OK to switch", () -> {
                    a.prefs.edit().putBoolean(Pip.PREF_ON_HOME, !Pip.onHome(a.prefs)).apply();
                    settings(a, pipRow);
                });
            }
            if (!ExternalPlayer.choice(a.prefs).isEmpty()) {
                final int forRow = list.getChildCount();
                row(a, list, "Use the external player for", ExternalPlayer.FOR_LABELS[ExternalPlayer.forIndex(ExternalPlayer.forKey(a.prefs))] + "  •  everything else plays in WygTV's own player", () ->
                        choose(a, "Use the external player for", ExternalPlayer.FOR_LABELS, ExternalPlayer.forIndex(ExternalPlayer.forKey(a.prefs)), i -> {
                            a.prefs.edit().putString(ExternalPlayer.PREF_FOR, ExternalPlayer.FOR_KEYS[i]).apply();
                            settings(a, forRow);
                        }));
            }
            final int formatRow = list.getChildCount();
            if (!m3u) {
                row(a, list, "Live stream format", FORMAT_LABELS[indexOf(FORMAT_KEYS, a.prefs.getString("live_format", "auto"))], () ->
                        choose(a, "Live stream format (try HLS if channels stutter or won't start)", FORMAT_LABELS, indexOf(FORMAT_KEYS, a.prefs.getString("live_format", "auto")), i -> {
                            a.prefs.edit().putString("live_format", FORMAT_KEYS[i]).apply();
                            settings(a, formatRow);
                        }));
            }
            final int bufferRow = list.getChildCount();
            final String bufferKey = a.prefs.getString(Players.PREF_BUFFER, Players.BUFFER_DEFAULT);
            row(a, list, "Buffer", Players.BUFFER_LABELS[Math.max(0, indexOf(Players.BUFFER_KEYS, bufferKey))] + "  •  more buffer rides out a shaky connection but takes longer to start a channel", () ->
                    choose(a, "Buffer before playing", Players.BUFFER_LABELS, Math.max(0, indexOf(Players.BUFFER_KEYS, bufferKey)), i -> {
                        a.prefs.edit().putString(Players.PREF_BUFFER, Players.BUFFER_KEYS[i]).apply();
                        settings(a, bufferRow);
                    }));
            final int decoderRow = list.getChildCount();
            final String decoderKey = a.prefs.getString(Players.PREF_DECODER, Players.DECODER_DEFAULT);
            row(a, list, "Video decoder", Players.DECODER_LABELS[Math.max(0, indexOf(Players.DECODER_KEYS, decoderKey))] + "  •  try Software first if a channel freezes, shows green blocks or won't play", () ->
                    choose(a, "Video decoder", Players.DECODER_LABELS, Math.max(0, indexOf(Players.DECODER_KEYS, decoderKey)), i -> {
                        a.prefs.edit().putString(Players.PREF_DECODER, Players.DECODER_KEYS[i]).apply();
                        settings(a, decoderRow);
                    }));
            final int subsLiveRow = list.getChildCount();
            row(a, list, "Subtitles for Live TV", onOff(Subtitles.defaultOn(a.prefs, true)) + "  •  closed captions start on for every channel that sends them. Press OK to switch", () -> {
                a.prefs.edit().putBoolean(Subtitles.PREF_LIVE, !Subtitles.defaultOn(a.prefs, true)).apply();
                settings(a, subsLiveRow);
            });
            final int subsVodRow = list.getChildCount();
            if (!liveOnly) row(a, list, "Subtitles for Movies and Series", onOff(Subtitles.defaultOn(a.prefs, false)) + "  •  press OK to switch. In the player, MENU > Subtitles picks a language", () -> {
                a.prefs.edit().putBoolean(Subtitles.PREF_VOD, !Subtitles.defaultOn(a.prefs, false)).apply();
                settings(a, subsVodRow);
            });
            final int subsLangRow = list.getChildCount();
            row(a, list, "Subtitle language", SubSources.langLabels()[Math.max(0, indexOf(SubSources.LANG_CODES, a.prefs.getString(SubSources.PREF_LANG, "")))] + "  •  picked first when a stream has several, and used to search online", () ->
                    choose(a, "Subtitle language", SubSources.langLabels(), Math.max(0, indexOf(SubSources.LANG_CODES, a.prefs.getString(SubSources.PREF_LANG, ""))), i -> {
                        a.prefs.edit().putString(SubSources.PREF_LANG, SubSources.LANG_CODES[i]).apply();
                        settings(a, subsLangRow);
                    }));
            final int subsSizeRow = list.getChildCount();
            row(a, list, "Subtitle text size", SubSources.SIZE_LABELS[SubSources.sizeIndex(a.prefs)], () ->
                    choose(a, "Subtitle text size", SubSources.SIZE_LABELS, SubSources.sizeIndex(a.prefs), i -> {
                        a.prefs.edit().putString(SubSources.PREF_SIZE, SubSources.SIZE_KEYS[i]).apply();
                        settings(a, subsSizeRow);
                    }));
            if (!liveOnly) {
                final int osRow = list.getChildCount();
                String osKey = a.saved(SubSources.PREF_OS_KEY).trim();
                row(a, list, "Online subtitles: OpenSubtitles", osKey.isEmpty() ? "Off  •  press OK to add a free OpenSubtitles.com API key (the biggest subtitle library)"
                        : "On  •  key …" + osKey.substring(Math.max(0, osKey.length() - 4)) + (a.saved(SubSources.PREF_OS_USER).isEmpty() ? "" : "  •  signed in as " + a.saved(SubSources.PREF_OS_USER)) + "  •  press OK to change",
                        () -> openSubtitlesKey(a, osRow));
                final int subdlRow = list.getChildCount();
                String sdKey = a.saved(SubSources.PREF_SUBDL_KEY).trim();
                row(a, list, "Online subtitles: SubDL", sdKey.isEmpty() ? "Off  •  press OK to add a free SubDL API key" : "On  •  key …" + sdKey.substring(Math.max(0, sdKey.length() - 4)) + "  •  press OK to change",
                        () -> subdlKey(a, subdlRow));
                note(a, list, "Podnapisi works without a key. In the player, MENU > Subtitles searches every source that's on, or loads a subtitle file from a link, the TV or a USB drive.");
            }
            if (!liveOnly) {
                final int watchedRow = list.getChildCount();
                row(a, list, "Watched series", (Watch.hideWatched(a) ? "Hide" : "Show with ✓") + "  •  series with every episode watched. Mark episodes, seasons or a whole series in its episode list", () -> {
                    a.prefs.edit().putBoolean(Watch.PREF_HIDE_WATCHED, !Watch.hideWatched(a)).apply();
                    settings(a, watchedRow);
                });
                final int skipRow = list.getChildCount();
                row(a, list, "Play next episode", (Watch.skipWatched(a) ? "Skip watched episodes" : "Always the very next episode") + "  •  press OK to switch", () -> {
                    a.prefs.edit().putBoolean(Watch.PREF_SKIP_WATCHED, !Watch.skipWatched(a)).apply();
                    settings(a, skipRow);
                });
            }
        } else if (g.equals(GROUP_APPEARANCE)) {
            final int themeRow = list.getChildCount();
            row(a, list, "Theme", Theme.LABELS[Theme.indexOf(a.prefs.getString("theme", Theme.DEFAULT))], () ->
                    choose(a, "Theme", Theme.LABELS, Theme.indexOf(a.prefs.getString("theme", Theme.DEFAULT)), i -> {
                        a.prefs.edit().putString("theme", Theme.KEYS[i]).apply();
                        a.applyTheme();
                        settings(a, themeRow);
                    }));
            final int glassRow = list.getChildCount();
            row(a, list, "Glass effect", onOff(Theme.glass) + "  •  frosted see-through panels and buttons over a softly blurred background. Press OK to switch", () -> {
                a.prefs.edit().putBoolean(Theme.PREF_GLASS, !Theme.glass).apply();
                a.applyTheme();
                settings(a, glassRow);
            });
            final int modeRow = list.getChildCount();
            if (!Brand.OTA) row(a, list, "App mode", liveOnly ? "Live TV only  •  opens on the TV guide; press OK for the full app" : "Full  •  press OK for Live TV only (opens on the TV guide, hides Movies and Series)", () -> {
                a.prefs.edit().putBoolean("live_only", !a.liveOnly()).apply();
                Toast.makeText(a, a.liveOnly() ? "Live TV only. Settings stays in the side menu to switch back." : "Full app is back.", Toast.LENGTH_LONG).show();
                settings(a, modeRow);
            });
            final int startRow = list.getChildCount();
            final String[] startKeys = liveOnly ? START_KEYS_LIVE : START_KEYS, startLabels = liveOnly ? START_LABELS_LIVE : START_LABELS;
            row(a, list, "Open at start-up", startLabels[indexOf(startKeys, a.prefs.getString("start_screen", "home"))], () ->
                    choose(a, "Open at start-up", startLabels, indexOf(startKeys, a.prefs.getString("start_screen", "home")), i -> {
                        a.prefs.edit().putString("start_screen", startKeys[i]).apply();
                        settings(a, startRow);
                    }));
            if (!liveOnly) {
                final int homeRow = list.getChildCount();
                row(a, list, "Main screen", Showcase.LABELS[Showcase.on(a.prefs) ? 0 : 1] + "  •  press OK to choose", () ->
                        choose(a, "Main screen", Showcase.LABELS, Showcase.on(a.prefs) ? 0 : 1, i -> {
                            a.prefs.edit().putString(Showcase.PREF, i == 0 ? "showcase" : "classic").apply();
                            settings(a, homeRow);
                        }));
            }
            final int orderRow = list.getChildCount();
            row(a, list, "Side menu order", "press OK to move side menu items up and down", () -> MenuOrder.edit(a, () -> settings(a, orderRow)));
            if (!liveOnly) {
                final int exitRow = list.getChildCount();
                row(a, list, "Exit button on Home", (MenuOrder.homeExit(a.prefs) ? "On" : "Off") + "  •  a small Exit at the bottom of the Home screen. The side menu always has one. Press OK to switch", () -> {
                    a.prefs.edit().putBoolean(MenuOrder.PREF_HOME_EXIT, !MenuOrder.homeExit(a.prefs)).apply();
                    settings(a, exitRow);
                });
            }
            final int guideRowsRow = list.getChildCount();
            final String[] guideRows = {"3", "4", "5"};
            row(a, list, "Live TV guide rows", a.prefs.getInt("guide_rows", 4) + " channels  •  more rows make the preview smaller", () ->
                    choose(a, "Channel rows in the Live TV guide", new String[]{"3 rows", "4 rows", "5 rows"}, indexOf(guideRows, String.valueOf(a.prefs.getInt("guide_rows", 4))), i -> {
                        a.prefs.edit().putInt("guide_rows", 3 + i).apply();
                        settings(a, guideRowsRow);
                    }));
            final int orderChRow = list.getChildCount();
            row(a, list, "Channel order", ChannelOrder.label(a.prefs) + "  •  how channels line up in the TV guide, Live TV and CH+/CH-", () ->
                    choose(a, "Channel order in the guides", ChannelOrder.LABELS, ChannelOrder.index(ChannelOrder.key(a.prefs)), i -> {
                        a.prefs.edit().putString(ChannelOrder.PREF, ChannelOrder.KEYS[i]).apply();
                        settings(a, orderChRow);
                    }));
            final int previewRow = list.getChildCount();
            row(a, list, "Live preview switches", PreviewDelay.label(a.prefs) + "  •  Live preview guide change delay", () ->
                    choose(a, "Change the live preview", PreviewDelay.LABELS, Menus.indexOf(PreviewDelay.KEYS, PreviewDelay.key(a.prefs)), i -> {
                        a.prefs.edit().putString(PreviewDelay.PREF, PreviewDelay.KEYS[i]).apply();
                        settings(a, previewRow);
                    }));
            final int tmdbRow = list.getChildCount();
            if (!Brand.OTA) keyRow(a, list, "Movie & series info (TMDb)", Tmdb.PREF_KEY, Tmdb.builtInKey(), "TMDb", "posters, plots, cast and ratings", tmdbRow);
            final int omdbRow = list.getChildCount();
            if (!Brand.OTA) keyRow(a, list, "IMDb ratings (OMDb)", Omdb.PREF_KEY, Omdb.builtInKey(), "OMDb", "IMDb, Rotten Tomatoes and Metacritic ratings, plus plots and cast", omdbRow);
        } else {
            row(a, list, "Back up playlists", "save playlists, favorites, your categories, My List, hidden items and all settings to a dated " + Brand.NAME + " backup file", () -> Backup.backup(a));
            row(a, list, "Restore from a backup", "reload everything from a " + Brand.NAME + " backup file", () -> Backup.restore(a));
            // The Galaxy Store build updates through the store, so it has no update rows.
            if (!Brand.STORE) {
                row(a, list, "Check for updates", Brand.NAME + " " + Updater.installedName(a) + "  •  press OK to look for a newer version and install it over this one", () -> Updater.check(a, true));
                final int autoRow = list.getChildCount();
                boolean auto = a.prefs.getBoolean(Updater.PREF_AUTO, true);
                row(a, list, "Look for updates at start-up", auto ? "On  •  offers new versions when " + Brand.NAME + " opens" : "Off", () -> {
                    a.prefs.edit().putBoolean(Updater.PREF_AUTO, !a.prefs.getBoolean(Updater.PREF_AUTO, true)).apply();
                    settings(a, autoRow);
                });
            }
            String version = Updater.installedName(a);
            final String about = Brand.NAME + " " + version + (Brand.OTA ? "  •  antenna TV from HDHomeRun" : " for Android TV");
            row(a, list, "About", about + "  •  made by Wyglaf", () -> aboutDialog(a, about));
        }
        int i = Math.max(0, Math.min(focusIndex, list.getChildCount() - 1));
        list.getChildAt(i).requestFocus();
    }

    /** Icon shown for each Settings group, on the top page and in the collapsed tree column. */
    static String groupIcon(String group) {
        switch (group) {
            case GROUP_PLAYLISTS: return "▤";
            case GROUP_PLAYER: return "▶";
            case GROUP_APPEARANCE: return "◐";
            default: return "⟳";
        }
    }

    /** The collapsed Settings tree on the left of a group page: All settings, then the four groups, {@code here} marked. */
    static void settingsTree(MainActivity a, String here) {
        TreeMenu.begin(a);
        TreeMenu.heading(a, "SETTINGS");
        TreeMenu.entry(a, "⚙", "All settings", false, () -> settings(a));
        for (String g : GROUPS) TreeMenu.entry(a, groupIcon(g), groupTitle(g), g.equals(here), () -> settingsGroup(a, g));
    }

    /** True while the Playlists page was opened from Settings > Manage playlists (Back returns there). */
    static boolean playlistsFromSettings;

    /** Back on the Playlists page: to Settings > Manage playlists when it was opened from there. */
    static boolean playlistsBack(MainActivity a) {
        if (!playlistsFromSettings) return false;
        playlistsFromSettings = false;
        settingsGroup(a, GROUP_PLAYLISTS);
        return true;
    }

    /** Back to Settings > Manage playlists, on the HDHomeRun row (after a tuner form or change). */
    static void backToTuners(MainActivity a) { settingsGroup = GROUP_PLAYLISTS; settings(a, 2); }

    static String groupTitle(String group) {
        switch (group) {
            case GROUP_PLAYLISTS: return "Manage playlists";
            case GROUP_PLAYER: return "Manage player";
            case GROUP_APPEARANCE: return "Appearance";
            default: return "Manage app";
        }
    }

    private static void keyRow(MainActivity a, LinearLayout list, String name, String pref, String builtIn, String service, String what, int row) {
        String mine = a.saved(pref).trim();
        row(a, list, name, !mine.isEmpty() ? "On  •  your " + service + " key …" + mine.substring(Math.max(0, mine.length() - 4)) + "  •  press OK to change or remove it"
                : !builtIn.isEmpty() ? "On  •  built-in key  •  press OK to use your own"
                : "Off  •  press OK to add a free " + service + " API key for " + what, () -> apiKey(a, row, pref, service));
    }

    /** Settings → TMDb / OMDb: type, test and save an API key. */
    private static void apiKey(MainActivity a, int focusRow, String pref, String service) {
        boolean tmdb = service.equals("TMDb");
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        TextView help = a.label(tmdb
                ? "Get a free key at themoviedb.org → Settings → API, then type the \"API Key\" here. Movie and series pages then show TMDb posters, plots, cast, ratings and age ratings."
                : "Get a free key at omdbapi.com → API Key (the free plan allows 1,000 lookups a day; WygTV saves each answer for two weeks). Movie and series pages then show IMDb, Rotten Tomatoes and Metacritic ratings.", 15);
        help.setTextColor(Theme.MUTED);
        box.addView(help, new LinearLayout.LayoutParams(-1, -2));
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setHint(service + " API key");
        field.setText(a.saved(pref));
        field.setTextColor(Theme.TEXT);
        field.setHintTextColor(Theme.HINT);
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        new Dialogs.Builder(a).setTitle(service + " API key").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String key = field.getText().toString().replaceAll("\\s+", "");
                    a.prefs.edit().putString(pref, key).apply();
                    settings(a, focusRow);
                    if (key.isEmpty()) return;
                    Toast.makeText(a, "Checking the key with " + service + "…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        boolean ok = tmdb ? Tmdb.works(key) : Omdb.works(key);
                        a.runOnUiThread(() -> Toast.makeText(a, ok ? service + " key works. Open any movie or series to see its details." : service + " didn't accept that key (or there's no connection). Check it and try again.", Toast.LENGTH_LONG).show());
                    }).start();
                })
                .setNeutralButton("Remove", (d, w) -> { a.prefs.edit().remove(pref).apply(); settings(a, focusRow); })
                .setNegativeButton("Cancel", null).show();
        field.requestFocus();
    }

    /** Settings → OpenSubtitles: API key, plus an optional account for more downloads a day. */
    private static void openSubtitlesKey(MainActivity a, int focusRow) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        TextView help = a.label("Make a free account at opensubtitles.com, then open opensubtitles.com/consumers, press \"New consumer\" and type its API key here. "
                + "Your username and password are optional: without them OpenSubtitles allows only a few downloads a day.", 15);
        help.setTextColor(Theme.MUTED);
        box.addView(help, new LinearLayout.LayoutParams(-1, -2));
        EditText key = keyField(a, box, "OpenSubtitles API key", a.saved(SubSources.PREF_OS_KEY));
        EditText user = keyField(a, box, "Username (optional)", a.saved(SubSources.PREF_OS_USER));
        EditText pass = keyField(a, box, "Password (optional)", a.saved(SubSources.PREF_OS_PASS));
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new Dialogs.Builder(a).setTitle("OpenSubtitles").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String k = key.getText().toString().replaceAll("\\s+", "");
                    a.prefs.edit().putString(SubSources.PREF_OS_KEY, k).putString(SubSources.PREF_OS_USER, user.getText().toString().trim())
                            .putString(SubSources.PREF_OS_PASS, pass.getText().toString()).apply();
                    settings(a, focusRow);
                    if (k.isEmpty()) return;
                    Toast.makeText(a, "Checking the key with OpenSubtitles…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        boolean ok = SubSources.openSubtitlesWorks(a.prefs);
                        a.runOnUiThread(() -> Toast.makeText(a, ok ? "OpenSubtitles key works. In the player, MENU > Subtitles > Search online." : "OpenSubtitles didn't accept that key (or there's no connection). Check it and try again.", Toast.LENGTH_LONG).show());
                    }).start();
                })
                .setNeutralButton("Remove", (d, w) -> {
                    a.prefs.edit().remove(SubSources.PREF_OS_KEY).remove(SubSources.PREF_OS_USER).remove(SubSources.PREF_OS_PASS).apply();
                    settings(a, focusRow);
                })
                .setNegativeButton("Cancel", null).show();
        key.requestFocus();
    }

    /** Settings → SubDL API key. */
    private static void subdlKey(MainActivity a, int focusRow) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        TextView help = a.label("Make a free account at subdl.com, open your account panel → API, and type the API key here.", 15);
        help.setTextColor(Theme.MUTED);
        box.addView(help, new LinearLayout.LayoutParams(-1, -2));
        EditText key = keyField(a, box, "SubDL API key", a.saved(SubSources.PREF_SUBDL_KEY));
        new Dialogs.Builder(a).setTitle("SubDL").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    a.prefs.edit().putString(SubSources.PREF_SUBDL_KEY, key.getText().toString().replaceAll("\\s+", "")).apply();
                    settings(a, focusRow);
                })
                .setNeutralButton("Remove", (d, w) -> { a.prefs.edit().remove(SubSources.PREF_SUBDL_KEY).apply(); settings(a, focusRow); })
                .setNegativeButton("Cancel", null).show();
        key.requestFocus();
    }

    private static EditText keyField(MainActivity a, LinearLayout box, String hint, String value) {
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setText(value);
        field.setTextColor(Theme.TEXT);
        field.setHintTextColor(Theme.HINT);
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private static void guideSource(MainActivity a, JSONObject active, Runnable after) {
        if (active == null) return;
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint("http://…/guide.xml or .xml.gz");
        input.setText(active.optString("epg"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        boolean m3u = Library.M3U.equals(active.optString("type"));
        new Dialogs.Builder(a).setTitle("TV guide (XMLTV) link for this playlist")
                .setMessage(m3u ? "Leave empty to use the guide the playlist names, if it has one." : "Leave empty to use the provider's own guide.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String link = input.getText().toString().trim();
                    if (!link.isEmpty() && !(link.startsWith("http://") || link.startsWith("https://"))) { alert(a, "Enter a link that starts with http:// or https://."); return; }
                    setGuide(a, active, link, after);
                })
                .setNeutralButton("Use default", (d, w) -> setGuide(a, active, "", after))
                .setNegativeButton("Cancel", null).show();
    }

    private static void setGuide(MainActivity a, JSONObject p, String link, Runnable after) {
        try { p.put("epg", link); } catch (JSONException ignored) { }
        a.library.put(p);
        if (p.optString("id").equals(a.library.activeId())) a.library.activate(p);
        new Thread(a.library::dropGuide).start();
        after.run();
    }

    /** Settings > Manage playlists: update now, how often, loading new playlists and guides, wait or background. */
    private static void updateRows(MainActivity a, LinearLayout list) {
        final int nowRow = list.getChildCount();
        row(a, list, "Update playlists and guides now", AutoRefresh.summary(a), () -> {
            if (AutoRefresh.running()) { Toast.makeText(a, "Already updating. The status line at the bottom shows how it's going.", Toast.LENGTH_LONG).show(); return; }
            boolean wait = AutoRefresh.waits(a.prefs);
            AutoRefresh.updateAll(a, wait ? () -> settings(a, nowRow) : null);
            if (!wait) settings(a, nowRow);
        });
        final int everyRow = list.getChildCount();
        int every = indexOf(AutoRefresh.EVERY_KEYS, AutoRefresh.every(a.prefs));
        row(a, list, "Automatic updates", AutoRefresh.EVERY_LABELS[every] + "  •  downloads every playlist and TV guide again, so channels and programs stay current", () ->
                choose(a, "Update playlists and TV guides", AutoRefresh.EVERY_LABELS, every, i -> {
                    a.prefs.edit().putString(AutoRefresh.PREF_EVERY, AutoRefresh.EVERY_KEYS[i]).apply();
                    settings(a, everyRow);
                }));
        final int addRow = list.getChildCount();
        boolean onAdd = AutoRefresh.onAdd(a.prefs);
        row(a, list, "New playlists and guides", (onAdd ? "Load right away" : "Load the first time they're opened") + "  •  press OK to switch", () -> {
            a.prefs.edit().putBoolean(AutoRefresh.PREF_ON_ADD, !AutoRefresh.onAdd(a.prefs)).apply();
            settings(a, addRow);
        });
        final int styleRow = list.getChildCount();
        int style = AutoRefresh.waits(a.prefs) ? 1 : 0;
        row(a, list, "While updating", AutoRefresh.STYLE_LABELS[style] + "  •  the status line at the bottom always shows progress, the last update and any problem", () ->
                choose(a, "While playlists and guides update", AutoRefresh.STYLE_LABELS, style, i -> {
                    a.prefs.edit().putString(AutoRefresh.PREF_STYLE, AutoRefresh.STYLE_KEYS[i]).apply();
                    settings(a, styleRow);
                }));
    }

    /** Settings → Manage player → Player: WygTV's own player (default), Android asks, or one installed video app. */
    private static void choosePlayer(MainActivity a, int focusRow) {
        java.util.List<android.content.pm.ResolveInfo> apps = ExternalPlayer.apps(a);
        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        keys.add(""); labels.add("WygTV built-in player (default)");
        for (android.content.pm.ResolveInfo r : apps) {
            keys.add(r.activityInfo.packageName);
            labels.add(r.loadLabel(a.getPackageManager()) + "  (external)");
        }
        keys.add(ExternalPlayer.ASK); labels.add("Ask which app every time");
        String current = ExternalPlayer.choice(a.prefs);
        if (!current.isEmpty() && !keys.contains(current)) { keys.add(1, current); labels.add(1, ExternalPlayer.appName(a, current) + "  (not installed)"); }
        choose(a, apps.isEmpty() ? "Player  •  no other video app found. Install VLC or MX Player from the store to use one" : "Play videos with",
                labels.toArray(new String[0]), keys.indexOf(current), i -> {
                    a.prefs.edit().putString(ExternalPlayer.PREF, keys.get(i)).apply();
                    settings(a, focusRow);
                });
    }

    // ------------------------------------------------------------------ extra (supplemental) TV guides

    /** Free XMLTV guides by country (epg.pw), offered when adding a link. */
    static final String[] FREE_GUIDE_LABELS = {"USA", "Canada", "United Kingdom", "Ireland", "Australia", "Mexico", "Spain", "Germany", "France", "Italy", "Portugal", "Brazil", "India"};
    static final String[] FREE_GUIDE_CODES = {"US", "CA", "GB", "IE", "AU", "MX", "ES", "DE", "FR", "IT", "PT", "BR", "IN"};

    static String freeGuideUrl(String code) { return "https://epg.pw/xmltv/epg_" + code + ".xml.gz"; }

    /** Settings → Extra TV guides: the saved links, add, priority. */
    static void extraGuides(MainActivity a, Runnable redraw) {
        java.util.List<String> urls = a.library.extras.urls();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String u : urls) {
            String st = a.library.extras.status(u);
            labels.add((labels.size() + 1) + ".  " + hostOf(u) + "  " + tail(u) + (st.isEmpty() ? "" : "   (" + st + ")"));
        }
        final int add = labels.size();
        labels.add("+ Add a guide link");
        labels.add("+ Add a free guide by country");
        final int prio = labels.size();
        labels.add(a.library.extras.first() ? "Order: extra guides first, then the playlist's  (press OK to change)"
                : "Order: the playlist's guide first, extra guides fill the gaps  (press OK to change)");
        new Dialogs.Builder(a).setTitle("Extra TV guides")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w < add) extraGuide(a, urls.get(w), redraw);
                    else if (w == add) addExtraGuide(a, redraw);
                    else if (w == add + 1) choose(a, "Free guide for which country?", FREE_GUIDE_LABELS, -1, i -> saveExtraGuide(a, freeGuideUrl(FREE_GUIDE_CODES[i]), redraw));
                    else if (w == prio) {
                        a.prefs.edit().putBoolean(ExtraGuides.PREF_FIRST, !a.library.extras.first()).apply();
                        redraw.run();
                        extraGuides(a, redraw);
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    private static String tail(String url) {
        int slash = url.lastIndexOf('/');
        return slash < 0 || slash == url.length() - 1 ? "" : url.substring(slash + 1);
    }

    private static void addExtraGuide(MainActivity a, Runnable redraw) {
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint("http://…/guide.xml or .xml.gz");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new Dialogs.Builder(a).setTitle("Add an extra TV guide")
                .setMessage("An XMLTV link. Channels are matched by their guide id (tvg-id), then by name. It is used for every playlist.")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String link = input.getText().toString().trim();
                    if (!(link.startsWith("http://") || link.startsWith("https://"))) { alert(a, "Enter a link that starts with http:// or https://."); return; }
                    saveExtraGuide(a, link, redraw);
                })
                .setNegativeButton("Cancel", null).show();
        input.requestFocus();
    }

    private static void saveExtraGuide(MainActivity a, String link, Runnable redraw) {
        java.util.List<String> urls = a.library.extras.urls();
        if (!urls.contains(link)) urls.add(link);
        ExtraGuides.save(a.prefs, urls);
        AutoRefresh.guideAdded(a, link, redraw, false);
    }

    private static void extraGuide(MainActivity a, String url, Runnable redraw) {
        java.util.List<String> urls = a.library.extras.urls();
        int at = urls.indexOf(url);
        String st = a.library.extras.status(url);
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add("Reload now");
        if (at > 0) opts.add("Move up (tried earlier)");
        opts.add("Remove");
        new Dialogs.Builder(a).setTitle(url + (st.isEmpty() ? "" : "  •  " + st))
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    String o = opts.get(w);
                    if (o.startsWith("Reload")) AutoRefresh.guideAdded(a, url, redraw, true);
                    else if (o.startsWith("Move")) {
                        urls.remove(at);
                        urls.add(at - 1, url);
                        ExtraGuides.save(a.prefs, urls);
                        extraGuides(a, redraw);
                    } else {
                        urls.remove(url);
                        ExtraGuides.save(a.prefs, urls);
                        a.library.extras.drop(url);
                        redraw.run();
                        extraGuides(a, redraw);
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    // ------------------------------------------------------------------ recently watched

    static void recent(MainActivity a) {
        a.mode = "recent";
        a.playerReturn = null;
        a.shell("RECENTLY WATCHED");
        LinearLayout list = column(a);
        JSONArray all = a.library.recent();
        if (all.length() == 0) {
            note(a, list, "Nothing yet. Channels, movies and episodes you watch show up here.");
            a.button("▣  Go to Live TV", list, () -> a.browse("live")).requestFocus();
            return;
        }
        note(a, list, "OK plays it again. Hold OK to remove it from this list.");
        JSONArray live = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null || !o.optString("type").equals("live")) continue;
            try { live.put(new JSONObject().put("stream_id", o.optString("id")).put("name", o.optString("title")).put("container_extension", o.optString("ext", "ts"))); }
            catch (JSONException ignored) { }
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("type"), id = o.optString("id"), title = o.optString("title", "Untitled"), ext = o.optString("ext");
            String icon = type.equals("live") ? "▣" : type.equals("vod") ? "▶" : "▤";
            String when = DateUtils.getRelativeTimeSpanString(o.optLong("at", now), now, DateUtils.MINUTE_IN_MILLIS).toString();
            TextView row = a.button(icon + "  " + (type.equals("live") ? (a.isFavorite(type, id) ? "★ " : "") : (a.inMyList(type, id) ? "☰ " : "")) + title + "      " + when, list, () -> {
                if (type.equals("live")) a.zapList = live;
                a.play(type, id, ext.isEmpty() ? (type.equals("live") ? "ts" : "mp4") : ext, title);
            });
            row.setTag(id);
            row.setOnLongClickListener(v -> {
                new Dialogs.Builder(a).setTitle(title).setItems(new String[]{"Remove from this list", "Cancel"}, (d, w) -> {
                    if (w == 0) { a.library.removeRecent(type, id); recent(a); }
                }).show();
                return true;
            });
        }
        a.button("Clear the list", list, () -> new Dialogs.Builder(a).setTitle("Clear Recently watched?")
                .setPositiveButton("Clear", (d, w) -> { a.library.clearRecent(); recent(a); }).setNegativeButton("Cancel", null).show());
        if (!a.focusResume(list)) list.getChildAt(1).requestFocus();
    }

    // ------------------------------------------------------------------ helpers

    private static LinearLayout column(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        a.body.addView(sv, new LinearLayout.LayoutParams(0, -1, 1));
        TreeMenu.page(sv);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(a.dp(40), 0, a.dp(40), 0);
        sv.addView(list);
        return list;
    }

    private static void note(MainActivity a, LinearLayout parent, String text) {
        TextView t = a.label(text, 15);
        t.setTextColor(Theme.MUTED);
        t.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(8));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /** About: the version, and Wyglaf (who makes WygTV) with his avatar. */
    private static void aboutDialog(MainActivity a, String about) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        box.setPadding(a.dp(22), a.dp(16), a.dp(22), a.dp(8));
        android.widget.ImageView pic = new android.widget.ImageView(a);
        pic.setImageResource(R.drawable.wyglaf_avatar);
        pic.setContentDescription("Wyglaf");
        pic.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        pic.setClipToOutline(true);
        pic.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        box.addView(pic, new LinearLayout.LayoutParams(a.dp(100), a.dp(100)));
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(a.dp(18), 0, 0, 0);
        TextView made = a.label("Made by Wyglaf", 20);
        made.setTypeface(null, android.graphics.Typeface.BOLD);
        made.setTextColor(Theme.ACCENT);
        text.addView(made);
        TextView ver = a.label(about, 15);
        ver.setTextColor(Theme.MUTED);
        text.addView(ver);
        box.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        new Dialogs.Builder(a).setTitle("About " + Brand.NAME).setView(box).setPositiveButton("OK", null).show();
    }

    private static void row(MainActivity a, LinearLayout list, String name, String value, Runnable action) {
        TextView v = a.button(name + "\n      " + value, list, action);
        v.setTextSize(19);
    }

    private static String onOff(boolean on) { return on ? "On" : "Off"; }

    interface Pick { void run(int index); }

    private static void choose(MainActivity a, String title, String[] labels, int current, Pick pick) {
        new Dialogs.Builder(a).setTitle(title).setSingleChoiceItems(labels, current, (d, which) -> { d.dismiss(); pick.run(which); })
                .setNegativeButton("Cancel", null).show();
    }

    static int indexOf(String[] keys, String key) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) return i;
        return 0;
    }

    static void alert(MainActivity a, String message) {
        new Dialogs.Builder(a).setMessage(message).setPositiveButton("OK", null).show();
    }
}

