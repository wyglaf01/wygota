package com.wygtv.tv;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Subtitles and closed captions in the full-screen player.
 *
 * Live channels usually carry closed captions (CEA-608/708) inside the video, which the player shows as a
 * subtitle track. Movies and series can have subtitle tracks of their own. Settings decides whether they
 * start switched on; in the player, MENU then "Subtitles" (or the CC / captions key) picks a track or turns them off.
 * Movies and episodes with no subtitles of their own can get them from {@link SubSources}: searched online,
 * from a link, or from a file on the TV or a USB drive. Settings also sets the language and the text size.
 */
@OptIn(markerClass = UnstableApi.class)
final class Subtitles {
    static final String PREF_LIVE = "subtitles_live", PREF_VOD = "subtitles_vod";

    static final int REQ_FILES = 7301;

    private final MainActivity a;
    private final ExoPlayer player;
    private final boolean live;
    private boolean wanted;      // show a subtitle track whenever the stream has one
    private boolean forcedHere;  // already picked a track for what is playing now
    private PlayerView view;
    private String type = "", title = "";
    private String waitingFor;   // label of a subtitle file just added, picked once the player lists it
    private final List<MediaItem.SubtitleConfiguration> added = new ArrayList<>();
    private SubSources.Query lastQuery;
    private android.widget.ImageButton ccButton; // CC in the on-screen playback controls

    private Subtitles(MainActivity a, ExoPlayer player, boolean live, boolean on) {
        this.a = a;
        this.player = player;
        this.live = live;
        player.addListener(new Player.Listener() {
            @Override public void onTracksChanged(Tracks tracks) { if (!pickAdded(tracks)) ensureShown(tracks); }
            @Override public void onMediaItemTransition(androidx.media3.common.MediaItem item, int reason) { forcedHere = false; }
        });
        set(on);
    }

    /** Sets up subtitles for a new player, switched on or off from the Settings choice for live or on-demand. */
    static Subtitles attach(MainActivity a, ExoPlayer player, boolean live) {
        return new Subtitles(a, player, live, defaultOn(a.prefs, live));
    }

    /** The player screen: where subtitles are drawn (for the text size) and what is playing (for online searches). */
    void useView(PlayerView v, String type, String title) {
        this.view = v;
        this.type = type == null ? "" : type;
        this.title = title == null ? "" : title;
        applySize();
        addCcButton(v);
    }

    /**
     * A CC button in the playback controls (D-pad Down while watching) that is always there, even when the stream has
     * no subtitles yet. OK opens the same Subtitles pop-up as MENU: on / off, the stream's tracks, and for movies and
     * episodes online search, a file or a link. Replaces Media3's own CC button, which greys out with no tracks.
     */
    private void addCcButton(PlayerView v) {
        ccButton = null;
        if (v == null) return;
        v.setShowSubtitleButton(false);
        android.view.View settings = v.findViewById(androidx.media3.ui.R.id.exo_settings);
        if (settings == null || !(settings.getParent() instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup row = (android.view.ViewGroup) settings.getParent();
        android.widget.ImageButton cc = new android.widget.ImageButton(a);
        if (settings.getBackground() != null && settings.getBackground().getConstantState() != null)
            cc.setBackground(settings.getBackground().getConstantState().newDrawable().mutate());
        cc.setPadding(settings.getPaddingLeft(), settings.getPaddingTop(), settings.getPaddingRight(), settings.getPaddingBottom());
        cc.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        cc.setFocusable(true);
        cc.setContentDescription("Subtitles / CC");
        cc.setOnClickListener(x -> choose());
        row.addView(cc, row.indexOfChild(settings), settings.getLayoutParams());
        ccButton = cc;
        refreshCc();
    }

    private void refreshCc() {
        if (ccButton != null) ccButton.setImageResource(wanted ? androidx.media3.ui.R.drawable.exo_ic_subtitle_on : androidx.media3.ui.R.drawable.exo_ic_subtitle_off);
    }

    /** Text size from Settings, the same for every stream (embedded sizes are ignored so it always applies). */
    private void applySize() {
        SubtitleView sv = view == null ? null : view.getSubtitleView();
        if (sv == null) return;
        sv.setApplyEmbeddedFontSizes(false);
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SubSources.SIZE_SCALE[SubSources.sizeIndex(a.prefs)]);
    }

    static boolean defaultOn(SharedPreferences prefs, boolean live) {
        return prefs.getBoolean(live ? PREF_LIVE : PREF_VOD, false);
    }

    boolean on() { return wanted; }

    /** Channel changes keep the player, so a new stream gets a fresh pick of track. */
    void newStream() { forcedHere = false; }

    /** On: prefer the TV's language, accept tracks with no language (most closed captions). Off: no text track. */
    private void set(boolean on) {
        wanted = on;
        forcedHere = false;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (on) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(SubSources.lang(a.prefs)).setSelectUndeterminedTextLanguage(true);
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        player.setTrackSelectionParameters(b.build());
        ensureShown(player.getCurrentTracks());
        refreshCc();
    }

    /** When subtitles are wanted but the language rules picked nothing, show the first track the stream has. */
    private void ensureShown(Tracks tracks) {
        if (!wanted || forcedHere || tracks == null || tracks.isTypeSelected(C.TRACK_TYPE_TEXT)) return;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                forcedHere = true;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i)).build());
                return;
            }
        }
    }

    /** The CC / captions key: on or off. */
    void toggle() {
        set(!wanted);
        toast(wanted ? (textTracks().isEmpty() ? "Subtitles on (this channel has none right now)" : "Subtitles on") : "Subtitles off");
    }

    /** MENU > Subtitles: Off, every subtitle track in the stream, then other places to get subtitles and the text size. */
    void choose() {
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> index = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        labels.add("Off");
        int checked = 0;
        for (Tracks.Group g : textTracks()) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                groups.add(g);
                index.add(i);
                labels.add(label(g.getTrackFormat(i), labels.size()));
                if (wanted && g.isTrackSelected(i)) checked = labels.size() - 1;
            }
        }
        int tracks = labels.size();
        if (groups.isEmpty()) labels.set(0, wanted ? "Off  (on now, but this stream has no subtitles right now)" : "Off");
        if (groups.isEmpty() && live) labels.add("On  (show them when the channel sends any)");
        final int onRow = groups.isEmpty() && live ? labels.size() - 1 : -1;
        final int search = !live ? labels.size() : -1;
        if (!live) {
            labels.add("🔍  Search online for subtitles…");
            labels.add("📁  From a file on this TV or a USB drive…");
            labels.add("🔗  From a link…");
        }
        final int size = labels.size();
        labels.add("Text size: " + SubSources.SIZE_LABELS[SubSources.sizeIndex(a.prefs)]);
        if (groups.isEmpty() && wanted && live) checked = onRow;
        String head = groups.isEmpty() ? (live ? "Subtitles  •  none in this channel right now" : "Subtitles  •  none in this video. Get some below") : "Subtitles";
        new Dialogs.Builder(a).setTitle(head).setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, w) -> {
            d.dismiss();
            if (w == 0) { set(false); return; }
            if (w == onRow) { set(true); return; }
            if (w == size) { chooseSize(); return; }
            if (search >= 0 && w == search) { searchOnline(null, null); return; }
            if (search >= 0 && w == search + 1) { pickFile(); return; }
            if (search >= 0 && w == search + 2) { askLink(); return; }
            if (w < 1 || w >= tracks) return;
            Tracks.Group g = groups.get(w - 1);
            wanted = true;
            forcedHere = true;
            refreshCc();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), index.get(w - 1))).build());
        }).show();
    }

    private void chooseSize() {
        new Dialogs.Builder(a).setTitle("Subtitle text size").setSingleChoiceItems(SubSources.SIZE_LABELS, SubSources.sizeIndex(a.prefs), (d, w) -> {
            d.dismiss();
            a.prefs.edit().putString(SubSources.PREF_SIZE, SubSources.SIZE_KEYS[w]).apply();
            applySize();
        }).show();
    }

    // ---------------------------------------------------------------- subtitles from outside the stream

    private boolean stillHere() { return a.subs == this && a.player == player; }

    /** Online search with the title (or the words typed), in the Settings language, across every source that's set up. */
    private void searchOnline(SubSources.Query asked, String askedLang) {
        SubSources.Query q = asked != null ? asked : lastQuery != null ? lastQuery
                : SubSources.queryFor(type, title, a.seriesTitle, a.watching != null ? a.watching[2] : "", a.watching != null ? a.watching[3] : "");
        lastQuery = q;
        String lang = askedLang != null ? askedLang : SubSources.lang(a.prefs);
        if (q.title.isEmpty()) { changeWords(q, lang); return; }
        toast("Looking for " + SubSources.langName(lang) + " subtitles for " + q.describe() + "…");
        new Thread(() -> {
            List<String> problems = new ArrayList<>();
            List<SubSources.Result> found = SubSources.search(a.prefs, q, lang, problems);
            a.runOnUiThread(() -> { if (stillHere()) showResults(q, lang, found, problems); });
        }).start();
    }

    private void showResults(SubSources.Query q, String lang, List<SubSources.Result> found, List<String> problems) {
        List<String> rows = new ArrayList<>();
        rows.add("✎  Search words: " + q.describe() + "  •  change");
        rows.add("🌐  Language: " + SubSources.langName(lang) + "  •  change");
        for (SubSources.Result r : found) rows.add(r.label());
        String head = found.isEmpty() ? "No subtitles found" : found.size() + " subtitles found";
        if (!problems.isEmpty()) head += "  •  " + android.text.TextUtils.join("; ", problems);
        if (SubSources.sources(a.prefs).size() == 1) head += "  •  more sources: add a free OpenSubtitles or SubDL key in Settings > Manage player";
        new Dialogs.Builder(a).setTitle(head).setItems(rows.toArray(new String[0]), (d, w) -> {
            if (w == 0) { changeWords(q, lang); return; }
            if (w == 1) { changeLanguage(q, lang); return; }
            SubSources.Result r = found.get(w - 2);
            toast("Downloading from " + r.source + "…");
            new Thread(() -> {
                try {
                    File f = SubSources.download(a, a.prefs, r, q);
                    a.runOnUiThread(() -> { if (stillHere()) add(f, r.lang, SubSources.langName(r.lang) + " • " + r.source); });
                } catch (Exception e) {
                    a.runOnUiThread(() -> Toast.makeText(a, "Couldn't get that subtitle: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }).setNegativeButton("Close", null).show();
    }

    private void changeWords(SubSources.Query q, String lang) {
        LinearLayout box = form();
        EditText words = field(box, "Movie or series name", q.title);
        EditText year = field(box, "Year (movies, optional)", q.year);
        EditText season = field(box, "Season (episodes)", q.season);
        EditText episode = field(box, "Episode", q.episode);
        new Dialogs.Builder(a).setTitle("Search for subtitles").setView(box).setPositiveButton("Search", (d, w) -> {
            SubSources.Query n = new SubSources.Query();
            n.title = words.getText().toString().trim();
            n.year = year.getText().toString().replaceAll("\\D", "");
            n.season = season.getText().toString().replaceAll("\\D", "");
            n.episode = episode.getText().toString().replaceAll("\\D", "");
            n.series = !n.season.isEmpty() || !n.episode.isEmpty();
            if (n.title.isEmpty()) return;
            searchOnline(n, lang);
        }).setNegativeButton("Cancel", null).show();
        words.requestFocus();
    }

    private void changeLanguage(SubSources.Query q, String lang) {
        String[] codes = java.util.Arrays.copyOfRange(SubSources.LANG_CODES, 1, SubSources.LANG_CODES.length);
        String[] names = new String[codes.length];
        int at = 0;
        for (int i = 0; i < codes.length; i++) { names[i] = SubSources.langName(codes[i]); if (codes[i].equals(lang)) at = i; }
        new Dialogs.Builder(a).setTitle("Subtitle language").setSingleChoiceItems(names, at, (d, w) -> { d.dismiss(); searchOnline(q, codes[w]); }).show();
    }

    void pickFile() {
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            toast("Allow WygTV to read files, then choose this again.");
            a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_FILES);
            return;
        }
        toast("Looking for subtitle files…");
        new Thread(() -> {
            List<File> files = SubSources.localFiles();
            a.runOnUiThread(() -> {
                if (!stillHere()) return;
                if (files.isEmpty()) {
                    Menus.alert(a, "No subtitle files found. Put .srt, .vtt, .ass or .ssa files in the Download folder or on a USB drive, then try again.");
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) {
                    File f = files.get(i), p = f.getParentFile();
                    names[i] = f.getName() + (p == null ? "" : "   •  " + p.getName());
                }
                new Dialogs.Builder(a).setTitle("Subtitle files").setItems(names, (d, w) -> {
                    File f = files.get(w);
                    new Thread(() -> {
                        try {
                            File saved = SubSources.fromFile(a, f, SubSources.lang(a.prefs));
                            a.runOnUiThread(() -> { if (stillHere()) add(saved, "", f.getName()); });
                        } catch (Exception e) {
                            a.runOnUiThread(() -> Toast.makeText(a, "Couldn't read that file: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                }).setNegativeButton("Close", null).show();
            });
        }).start();
    }

    private void askLink() {
        LinearLayout box = form();
        EditText link = field(box, "Link to a .srt, .vtt, .ass file or a .zip with one", "http://");
        new Dialogs.Builder(a).setTitle("Subtitles from a link").setView(box).setPositiveButton("Load", (d, w) -> {
            String url = link.getText().toString().trim();
            if (!url.matches("(?i)https?://.+")) { toast("That isn't a web link."); return; }
            toast("Downloading…");
            new Thread(() -> {
                try {
                    File f = SubSources.fromLink(a, url);
                    a.runOnUiThread(() -> { if (stillHere()) add(f, "", "From a link"); });
                } catch (Exception e) {
                    a.runOnUiThread(() -> Toast.makeText(a, "Couldn't load that link: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }).setNegativeButton("Cancel", null).show();
        link.requestFocus();
        link.setSelection(link.getText().length());
    }

    /** Adds a subtitle file to what's playing and switches to it, carrying on from the same moment. */
    private void add(File f, String lang, String label) {
        MediaItem item = player.getCurrentMediaItem();
        if (item == null) return;
        String ext = SubSources.extensionOf(f.getName());
        String mime = ext.equals("vtt") ? MimeTypes.TEXT_VTT : ext.equals("ass") || ext.equals("ssa") ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP;
        String name = label + (added.isEmpty() ? "" : " (" + (added.size() + 1) + ")");
        added.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(mime)
                .setLanguage(lang == null || lang.isEmpty() ? null : lang)
                .setLabel(name)
                .setId("wygtv-sub-" + added.size())
                .build());
        long at = Math.max(0, player.getCurrentPosition());
        boolean playing = player.getPlayWhenReady();
        waitingFor = name;
        wanted = true;
        refreshCc();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        player.setMediaItem(item.buildUpon().setSubtitleConfigurations(new ArrayList<>(added)).build(), at);
        player.prepare();
        player.setPlayWhenReady(playing);
        toast("Subtitles loaded: " + label);
    }

    /** Switches to a subtitle file just added, once the player lists it. */
    private boolean pickAdded(Tracks tracks) {
        if (waitingFor == null || tracks == null) return false;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!waitingFor.equals(g.getTrackFormat(i).label)) continue;
                waitingFor = null;
                forcedHere = true;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i)).build());
                return true;
            }
        }
        return false;
    }

    private LinearLayout form() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        return box;
    }

    private EditText field(LinearLayout box, String hint, String value) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextColor(Theme.TEXT);
        e.setHintTextColor(Theme.HINT);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private List<Tracks.Group> textTracks() {
        List<Tracks.Group> out = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_TEXT && g.isSupported()) out.add(g);
        return out;
    }

    /** "English (CC)", "Spanish", "Track 2", plus the stream's own label when it has one. */
    static String label(Format f, int number) { return label(f.language, f.label, f.sampleMimeType, f.roleFlags, f.selectionFlags, number); }

    static String label(String language, String trackLabel, String mimeType, int roleFlags, int selectionFlags, int number) {
        String name = "";
        if (language != null && !language.isEmpty() && !C.LANGUAGE_UNDETERMINED.equals(language)) {
            name = new Locale(language).getDisplayLanguage();
            if (name.isEmpty()) name = language;
        }
        if (trackLabel != null && !trackLabel.isEmpty() && !trackLabel.equalsIgnoreCase(name)) name = name.isEmpty() ? trackLabel : name + " • " + trackLabel;
        String mime = mimeType == null ? "" : mimeType;
        boolean cc = MimeTypes.APPLICATION_CEA608.equals(mime) || MimeTypes.APPLICATION_CEA708.equals(mime) || mime.contains("cea-608") || mime.contains("cea-708");
        if (name.isEmpty()) name = cc ? "Closed captions" : "Track " + number;
        else if (cc) name += " (CC)";
        if ((roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 && !cc) name += " (SDH)";
        if ((selectionFlags & C.SELECTION_FLAG_FORCED) != 0) name += " (forced)";
        return name;
    }

    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }
}
