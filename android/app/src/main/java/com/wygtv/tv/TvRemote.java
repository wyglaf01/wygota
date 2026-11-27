package com.wygtv.tv;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.MediaItem;
import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Android TV remote handling for the whole app.
 *
 * Full-screen player: CH+/CH- (or D-pad up) change channel, D-pad down shows the playback controls, digit keys type a channel number,
 * OK/Info shows the channel banner, Guide opens the TV guide, Last returns to the previous channel,
 * Menu opens options, and the media keys play, pause, rewind and fast-forward.
 * List screens: CH+/CH- and Page keys jump ten rows, Menu opens a row's options, Guide and Search jump
 * straight to the TV guide and channel search.
 */
@OptIn(markerClass = UnstableApi.class) // PlayerView controller show/hide/visibility calls
final class TvRemote {
    private static final int BANNER_MS = 5000, NUMBER_MS = 1800, LIST_JUMP = 10;
    private static final long SEEK_SHORT_MS = 10_000, SEEK_LONG_MS = 30_000;

    private final MainActivity a;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // What the player is showing.
    private PlayerView view;
    private String type = "", id = "", title = "", ext = "";
    private JSONArray zapList;
    private int zapIndex = -1;
    private JSONObject lastChannel;
    private int epgTicket;

    // Overlays.
    private LinearLayout banner;
    private TextView bannerLogo, bannerTitle, bannerSource, bannerNow, bannerNext, numberBox;
    private String typed = "";
    private final Runnable hideBanner = () -> { if (banner != null) banner.setVisibility(View.GONE); };
    private final Runnable commitNumber = this::commitNumber;

    // Series: the "Up next" card shown near the end of an episode. OK plays the next episode, Back hides the card;
    // when the episode finishes it starts the next one by itself after a short countdown.
    private static final long UP_NEXT_MS = 40_000;
    private static final int AUTO_NEXT_SECONDS = 8;
    private TextView upNext;
    private boolean upNextDismissed;
    private int endedSeconds = -1;
    private final Runnable upNextTick = this::upNextTick;

    // Full live channel list, loaded in the background so channel numbers can reach any channel.
    private JSONArray allLive;
    private String allLiveAccount = "";
    private boolean loadingAllLive;

    TvRemote(MainActivity a) { this.a = a; }

    String currentId() { return id; }
    String currentType() { return type; }
    String currentExt() { return ext; }
    String currentTitle() { return title; }
    /** The channel list CH+/CH- walks through (null for movies and episodes). */
    JSONArray currentList() { return zapList; }
    PlayerView currentView() { return view; }

    /** Picture-in-picture: no banner, number box, Up next or playback controls in the small window. */
    void pictureInPicture(boolean in) {
        if (view == null) return;
        if (in) {
            ui.removeCallbacksAndMessages(null);
            view.hideController();
            view.setUseController(false);
            if (banner != null) banner.setVisibility(View.GONE);
            if (numberBox != null) numberBox.setVisibility(View.GONE);
            if (upNext != null) upNext.setVisibility(View.GONE);
            typed = "";
        } else {
            view.setUseController(true);
            if (isLive()) view.setControllerAutoShow(false);
            view.requestFocus();
        }
    }

    /** The player menu's window choices: the in-app mini player, and picture-in-picture where the device has it. */
    private String[] windowItems() {
        return Pip.supported(a) ? new String[]{"▣  Mini player (keep watching while you browse)", "⧉  Picture-in-picture (keep watching outside " + Brand.NAME + ")"}
                : new String[]{"▣  Mini player (keep watching while you browse)"};
    }

    private static String[] join(String[] first, String[] then) {
        String[] out = new String[first.length + then.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(then, 0, out, first.length, then.length);
        return out;
    }

    /** True (and done) when a player-menu pick was a window choice. */
    private boolean windowPick(String pick) {
        if (pick.startsWith("▣")) { MiniPlayer.shrink(a); return true; }
        if (pick.startsWith("⧉")) { if (!Pip.enter(a)) toast("Picture-in-picture isn't available on this device."); return true; }
        return false;
    }

    // ---------------------------------------------------------------- player screen

    /** Wraps the PlayerView with the channel banner and number overlay, and remembers the channel list for zapping. */
    View playerScreen(PlayerView playerView, String type, String id, String ext, String title, JSONArray list) {
        this.view = playerView;
        this.type = type;
        this.id = id;
        this.ext = ext;
        this.title = title;
        this.typed = "";
        this.lastChannel = null;
        ui.removeCallbacksAndMessages(null);
        playerView.setKeepScreenOn(true); // stop the TV screensaver from starting while something plays
        playerView.setControllerShowTimeoutMs(4000);
        playerView.setShowSubtitleButton(true); // Media3 CC button; Subtitles.useView swaps it for WygTV's own always-on CC button
        if (a.subs != null) a.subs.useView(playerView, type, title);

        zapList = null;
        zapIndex = -1;
        if (isLive()) {
            if (indexOf(list, id) >= 0) zapList = list;
            else if (allLiveReady() && indexOf(allLive, id) >= 0) zapList = visible(allLive);
            zapIndex = indexOf(zapList, id);
        }

        FrameLayout frame = new FrameLayout(a);
        frame.setBackgroundColor(0xff000000);
        frame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        buildBanner(frame);
        numberBox = text("", 44, Theme.ACCENT, true);
        numberBox.setGravity(Gravity.CENTER);
        numberBox.setBackground(panel());
        numberBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(a.dp(170), a.dp(90), Gravity.TOP | Gravity.END);
        nlp.setMargins(0, a.dp(28), a.dp(36), 0);
        frame.addView(numberBox, nlp);
        buildUpNext(frame);

        if (isLive()) {
            playerView.setControllerAutoShow(false);
            ensureAllLive();
            showBanner();
        }
        return frame;
    }

    private void buildBanner(FrameLayout frame) {
        banner = new LinearLayout(a);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(a.dp(26), a.dp(16), a.dp(26), a.dp(16));
        banner.setBackground(panel());
        banner.setVisibility(View.GONE);
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // The channel's logo from the playlist (or nothing), never its number.
        bannerLogo = text("", 30, Theme.ACCENT, true);
        top.addView(bannerLogo, new LinearLayout.LayoutParams(-2, -2));
        bannerTitle = text("", 28, Theme.TEXT, true);
        bannerTitle.setSingleLine(true);
        bannerTitle.setPadding(a.dp(4), 0, 0, 0);
        top.addView(bannerTitle, new LinearLayout.LayoutParams(0, -2, 1));
        banner.addView(top);
        bannerSource = text("", 15, Theme.ACCENT, false);
        bannerSource.setSingleLine(true);
        bannerSource.setPadding(0, a.dp(2), 0, a.dp(4));
        banner.addView(bannerSource);
        bannerNow = text("", 19, Theme.TEXT, false);
        bannerNow.setSingleLine(true);
        banner.addView(bannerNow);
        bannerNext = text("", 16, Theme.MUTED, false);
        bannerNext.setSingleLine(true);
        banner.addView(bannerNext);
        TextView hints = text(isLive()
                ? "▲ / CH± change channel    ▼ controls    0-9 channel number    OK resume / favorites / groups    INFO channel info    GUIDE TV guide    MENU options / subtitles    BACK exit"
                : "series".equals(type) && Episodes.next(a) != null ? "◀▶ seek    ▶❚❚ play / pause    MENU next episode / options / subtitles    BACK exit"
                : "◀▶ seek    ▶❚❚ play / pause    MENU options / subtitles    BACK exit", 14, Theme.MUTED, false);
        hints.setPadding(0, a.dp(8), 0, 0);
        banner.addView(hints);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(a.dp(36), 0, a.dp(36), a.dp(28));
        frame.addView(banner, lp);
    }

    private void buildUpNext(FrameLayout frame) {
        upNext = text("", 20, Theme.TEXT, true);
        upNext.setSingleLine(false);
        upNext.setMaxLines(3);
        upNext.setPadding(a.dp(22), a.dp(14), a.dp(22), a.dp(14));
        upNext.setBackground(panel());
        upNext.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(a.dp(420), -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, a.dp(36), a.dp(120));
        frame.addView(upNext, lp);
        upNextDismissed = false;
        endedSeconds = -1;
        if ("series".equals(type) && Episodes.next(a) != null) ui.postDelayed(upNextTick, 1000);
    }

    /** Once a second while an episode plays: shows the Up next card in its last 40 seconds, and counts down at the end. */
    private void upNextTick() {
        if (view == null || a.player == null || upNext == null) return;
        String label = Episodes.nextLabel(a);
        if (label == null) return;
        long dur = a.player.getDuration(), pos = a.player.getCurrentPosition();
        boolean ended = a.player.getPlaybackState() == Player.STATE_ENDED;
        boolean near = ended || (dur > 0 && dur - pos <= UP_NEXT_MS);
        if (!near) { endedSeconds = -1; upNextDismissed = false; upNext.setVisibility(View.GONE); }
        else if (!upNextDismissed) {
            if (ended) {
                if (endedSeconds < 0) endedSeconds = AUTO_NEXT_SECONDS;
                if (endedSeconds == 0) { Episodes.playNext(a); return; }
                upNext.setText("Up next   " + label + "\nStarting in " + endedSeconds-- + "…   OK play now   BACK cancel");
            } else upNext.setText("Up next   " + label + "\nOK play next episode   BACK hide");
            upNext.setVisibility(View.VISIBLE);
        }
        ui.postDelayed(upNextTick, 1000);
    }

    private boolean upNextShowing() { return upNext != null && upNext.getVisibility() == View.VISIBLE; }

    private void showBanner() {
        if (banner == null) return;
        JSONObject ch = channel();
        String icon = ch.optString("stream_icon").trim();
        bannerLogo.setVisibility(icon.isEmpty() ? View.GONE : View.VISIBLE);
        ChannelIcons.attach(bannerLogo, icon, title, 48);
        bannerTitle.setText(title);
        String source = Library.sourceLabel(a.library.find(a.library.activeId()));
        bannerSource.setText(source.isEmpty() ? "" : "Source  " + source);
        bannerSource.setVisibility(source.isEmpty() ? View.GONE : View.VISIBLE);
        banner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, BANNER_MS);
        if (isLive()) loadNowNext();
        else { bannerNow.setText(""); bannerNext.setText(""); }
    }

    private void loadNowNext() {
        final int ticket = ++epgTicket;
        final String forId = id;
        bannerNow.setText("Loading what's on…");
        bannerNext.setText("");
        a.apiQuiet("get_short_epg&stream_id=" + a.enc(forId) + "&limit=2", o -> {
            if (ticket != epgTicket || !forId.equals(id)) return;
            JSONArray list = o == null ? null : o.optJSONArray("epg_listings");
            bannerNow.setText(list == null || list.length() == 0 ? "No guide information" : "Now  " + epgLine(list.optJSONObject(0)));
            bannerNext.setText(list != null && list.length() > 1 ? "Next  " + epgLine(list.optJSONObject(1)) : "");
        });
    }

    private String epgLine(JSONObject e) {
        if (e == null) return "";
        String t = a.epgText(e.optString("title"));
        long start = e.optLong("start_timestamp", 0) * 1000, stop = e.optLong("stop_timestamp", 0) * 1000;
        if (start <= 0 || stop <= 0) return t;
        java.text.DateFormat f = android.text.format.DateFormat.getTimeFormat(a);
        return f.format(new java.util.Date(start)) + " – " + f.format(new java.util.Date(stop)) + "   " + t;
    }

    // ---------------------------------------------------------------- keys

    /** Called from Activity.dispatchKeyEvent before any view sees the key. Returns true when the key was used. */
    boolean handleKey(KeyEvent e) {
        int key = e.getKeyCode();
        if (a.mode.equals("player") && a.player != null && view != null) return playerKey(e, key);
        // Up/Down in a menu column (side menu, Live TV / Movies / Series menu) stay in it; only sideways leaves.
        if (FocusWrap.menuKey(a.getCurrentFocus(), e)) return true;
        // Live TV: Left from a playlist's categories goes to the compacted first tier beside it, Right comes back.
        if (LiveMenu.sideways(a, e)) return true;
        // Settings pages (and other pages opened from a list): Left goes to the collapsed menu tree beside them.
        if (TreeMenu.sideways(a, e)) return true;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        return listKey(e, key);
    }

    private boolean playerKey(KeyEvent e, int key) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean controls = view.isControllerFullyVisible();
        int digit = RemoteKeys.digit(key);
        if (isLive() && digit >= 0) { if (down) typeDigit(digit); return true; }
        switch (key) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down && isLive()) zap(1);
                return isLive();
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down && isLive()) zap(-1);
                return isLive();
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!isLive() || controls) return false;
                if (down) zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Down shows the playback controls (live too) instead of changing channel; CH- still goes down a channel.
                if (controls) return false;
                if (down && e.getRepeatCount() == 0) { hideBanner.run(); view.showController(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (controls) return false;
                if (upNextShowing()) { if (down && e.getRepeatCount() == 0) Episodes.playNext(a); return true; }
                if (!typed.isEmpty()) { if (down) { ui.removeCallbacks(commitNumber); commitNumber(); } return true; }
                if (!isLive()) return false; // movies and episodes: OK shows the playback controls
                if (down && e.getRepeatCount() == 0) channelOptions();
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (down) { if (banner.getVisibility() == View.VISIBLE) hideBanner.run(); else showBanner(); }
                return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (down && isLive()) lastChannel();
                return isLive();
            case KeyEvent.KEYCODE_GUIDE:
                if (down && isLive()) openGuideFromPlayer();
                return isLive();
            case KeyEvent.KEYCODE_MENU:
                if (down) playerMenu();
                return true;
            case KeyEvent.KEYCODE_WINDOW: // the Window / PiP key some remotes have: shrink to the mini player
                if (down && e.getRepeatCount() == 0) MiniPlayer.shrink(a);
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
                if (down && e.getRepeatCount() == 0 && a.subs != null) a.subs.toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (down) { if (a.player.isPlaying()) a.player.pause(); else a.player.play(); flashControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) { a.player.play(); flashControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) { a.player.pause(); flashControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (!down) a.onBackPressed();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seek(key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? SEEK_LONG_MS : -SEEK_LONG_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (!isLive()) {
                    boolean hasNext = Episodes.next(a) != null;
                    if (down && hasNext && e.getRepeatCount() == 0) Episodes.playNext(a);
                    return hasNext;
                }
                if (down) zap(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (!isLive()) return false;
                if (down) zap(key == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isLive() || controls) return false;
                if (down) seek(key == KeyEvent.KEYCODE_DPAD_RIGHT ? SEEK_SHORT_MS : -SEEK_SHORT_MS);
                return true;
            default:
                return false;
        }
    }

    /** Back in the player: first close the number entry or the playback controls, and only then leave. */
    boolean onBack() {
        if (!a.mode.equals("player") || view == null) return false;
        if (!typed.isEmpty()) { ui.removeCallbacks(commitNumber); typed = ""; numberBox.setVisibility(View.GONE); return true; }
        if (view.isControllerFullyVisible()) { view.hideController(); return true; }
        if (upNextShowing()) { upNextDismissed = true; endedSeconds = -1; upNext.setVisibility(View.GONE); return true; }
        return false;
    }

    /** The player is closing: remember where we were so the previous screen can focus the same channel. */
    void onPlayerExit() {
        ui.removeCallbacksAndMessages(null);
        a.resumeStreamId = id;
        if (a.tvGuide != null && isLive()) a.tvGuide.focusStream(id);
        view = null;
        banner = null;
        upNext = null;
    }

    private void flashControls() { if (!isLive()) view.showController(); }

    private void seek(long by) {
        if (isLive() || !a.player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return;
        long duration = a.player.getDuration();
        long target = Math.max(0, a.player.getCurrentPosition() + by);
        if (duration > 0) target = Math.min(duration, target);
        a.player.seekTo(target);
        view.showController();
    }

    // ---------------------------------------------------------------- channel changes

    private boolean isLive() { return "live".equals(type); }

    private JSONObject current() { return zapList == null || zapIndex < 0 ? null : zapList.optJSONObject(zapIndex); }

    private void zap(int delta) {
        if (zapList == null || zapList.length() < 2 || zapIndex < 0) {
            Toast.makeText(a, "Open a channel list to change channels with the remote.", Toast.LENGTH_SHORT).show();
            return;
        }
        switchTo(zapList, RemoteKeys.wrap(zapIndex, delta, zapList.length()));
    }

    private void lastChannel() {
        if (lastChannel == null) return;
        JSONObject target = lastChannel;
        String targetId = target.optString("stream_id");
        if (indexOf(zapList, targetId) >= 0) switchTo(zapList, indexOf(zapList, targetId));
        else if (allLiveReady() && indexOf(allLive, targetId) >= 0) { JSONArray all = visible(allLive); switchTo(all, indexOf(all, targetId)); }
    }

    private void switchTo(JSONArray list, int index) {
        JSONObject ch = list == null ? null : list.optJSONObject(index);
        if (ch == null || a.player == null) return;
        JSONObject was = current();
        if (was != null) lastChannel = was;
        else if (!id.isEmpty()) lastChannel = channelStub(id, title, ext);
        zapList = list;
        zapIndex = index;
        id = ch.optString("stream_id");
        title = ch.optString("name", "Channel");
        ext = ch.optString("container_extension", "ts");
        if (ext.isEmpty()) ext = "ts";
        if (a.subs != null) a.subs.newStream();
        if (a.recovery != null) a.recovery.newStream();
        a.player.setMediaItem(MediaItem.fromUri(a.streamUrl("live", id, ext)));
        a.player.prepare();
        a.player.play();
        a.library.recordPlay("live", id, ext, title);
        view.hideController();
        showBanner();
    }

    private static JSONObject channelStub(String id, String title, String ext) {
        JSONObject o = new JSONObject();
        try { o.put("stream_id", id).put("name", title).put("container_extension", ext); } catch (org.json.JSONException ignored) {}
        return o;
    }

    private void typeDigit(int digit) {
        typed = RemoteKeys.appendDigit(typed, digit);
        numberBox.setText(typed);
        numberBox.setVisibility(View.VISIBLE);
        ui.removeCallbacks(commitNumber);
        ui.postDelayed(commitNumber, NUMBER_MS);
    }

    private void commitNumber() {
        String wanted = typed;
        typed = "";
        if (numberBox != null) numberBox.setVisibility(View.GONE);
        if (wanted.isEmpty() || view == null) return;
        // The provider's channel number first, within the list you are watching, then across all channels.
        int i = RemoteKeys.indexOfNumber(numbers(zapList), wanted);
        if (i >= 0) { switchTo(zapList, i); return; }
        if (allLiveReady()) {
            JSONArray all = visible(allLive);
            i = RemoteKeys.indexOfNumber(numbers(all), wanted);
            if (i >= 0) { switchTo(all, i); return; }
        }
        // Providers without channel numbers: use the position in the list.
        int pos = parse(wanted) - 1;
        if (zapList != null && pos >= 0 && pos < zapList.length() && RemoteKeys.indexOfNumber(numbers(zapList), "1") < 0) { switchTo(zapList, pos); return; }
        Toast.makeText(a, loadingAllLive ? "Still loading the channel list, try again in a moment." : "No channel " + wanted, Toast.LENGTH_SHORT).show();
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; } }

    /** The channel playing, with its logo and playlist when known (from the list you zap through, else all channels). */
    private JSONObject channel() {
        JSONObject ch = current();
        if (ch != null && id.equals(ch.optString("stream_id"))) return ch;
        if (allLiveReady()) { int i = indexOf(allLive, id); if (i >= 0) return allLive.optJSONObject(i); }
        return channelStub(id, title, ext);
    }

    /**
     * OK on a live channel: Resume, Favorites, Add to channel group and New channel group (the guide pop-up
     * without Hidden), with the channel banner shown under it for the logo, source and what's on.
     */
    private void channelOptions() {
        showBanner();
        ui.removeCallbacks(hideBanner);
        android.app.AlertDialog d = GuideOptions.showInPlayer(a, channel(), this::restart);
        if (d == null) { ui.postDelayed(hideBanner, BANNER_MS); return; }
        d.setOnDismissListener(x -> { ui.removeCallbacks(hideBanner); ui.postDelayed(hideBanner, BANNER_MS); });
    }

    /** Start the channel playing again from scratch (a fresh connection to the stream). */
    private void restart() {
        if (a.player == null || view == null || id.isEmpty()) return;
        if (a.recovery != null) a.recovery.newStream();
        a.player.stop();
        a.player.setMediaItem(MediaItem.fromUri(a.streamUrl("live", id, ext)));
        a.player.prepare();
        a.player.play();
        Toast.makeText(a, "Resuming " + title + "…", Toast.LENGTH_SHORT).show();
    }

    private static String[] numbers(JSONArray list) {
        if (list == null) return new String[0];
        String[] out = new String[list.length()];
        for (int i = 0; i < out.length; i++) { JSONObject o = list.optJSONObject(i); out[i] = o == null ? "" : o.optString("num"); }
        return out;
    }

    static int indexOf(JSONArray list, String streamId) {
        if (list == null || streamId == null) return -1;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && streamId.equals(o.optString("stream_id"))) return i;
        }
        return -1;
    }

    private boolean allLiveReady() { return allLive != null && allLiveAccount.equals(a.accountPrefix()); }

    /** All live channels minus hidden channels and hidden categories. */
    private JSONArray visible(JSONArray all) { return a.withoutHiddenChannels(a.withoutHiddenIn("live", all)); }

    private void ensureAllLive() {
        if (allLiveReady() || loadingAllLive) return;
        if (a.liveIndex != null && a.liveIndexAccount.equals(a.accountPrefix())) { setAllLive(a.liveIndex); return; }
        loadingAllLive = true;
        final String account = a.accountPrefix();
        a.apiQuiet("get_live_streams", o -> {
            loadingAllLive = false;
            JSONArray list = o == null ? null : o.optJSONArray("list");
            if (list != null && account.equals(a.accountPrefix())) setAllLive(list);
        });
    }

    private void setAllLive(JSONArray list) {
        allLive = list;
        allLiveAccount = a.accountPrefix();
        if (zapList == null && view != null && isLive()) {
            JSONArray all = visible(list);
            int i = indexOf(all, id);
            if (i >= 0) { zapList = all; zapIndex = i; }
        }
    }

    // ---------------------------------------------------------------- player menu and guide

    private void playerMenu() {
        ui.removeCallbacks(hideBanner);
        if (!isLive()) {
            // Favorites are for Live TV; a movie (or the series of this episode) goes in My List instead.
            boolean series = "series".equals(type);
            String listType = series ? "series" : "vod", listId = series ? (a.watching != null ? a.watching[0] : "") : id;
            JSONObject listItem = new JSONObject();
            try {
                listItem.put(series ? "series_id" : "stream_id", listId).put("name", series && a.seriesTitle != null ? a.seriesTitle : title);
                if (!series) listItem.put("container_extension", ext);
            } catch (Exception ignored) { }
            boolean mine = !listId.isEmpty() && a.inMyList(listType, listId);
            String next = series ? Episodes.nextLabel(a) : null;
            String[] items = {mine ? "Remove from My List" : "Add to My List", subtitlesItem(), "Exit player"};
            if (next != null) items = new String[]{"▶  Play next episode   " + next, items[0], items[1], items[2]};
            final int shift = next != null ? 1 : 0;
            final String[] base = items, windows = windowItems();
            new Dialogs.Builder(a).setTitle(title)
                    .setItems(join(windows, base), (d, picked) -> {
                        if (picked < windows.length) { windowPick(windows[picked]); return; }
                        int which = picked - windows.length;
                        if (shift == 1 && which == 0) { Episodes.playNext(a); return; }
                        int w = which - shift;
                        if (w == 0) { if (!listId.isEmpty()) toast(a.toggleMyList(listType, listItem) ? "Added to My List" : "Removed from My List"); }
                        else if (w == 1) { if (a.subs != null) a.subs.choose(); }
                        else a.onBackPressed();
                    }).show();
            return;
        }
        boolean fav = a.isFavorite("live", id);
        final String[] windows = windowItems();
        new Dialogs.Builder(a).setTitle(title)
                .setItems(join(windows, new String[]{fav ? "Remove from Favorites" : "Add to Favorites", subtitlesItem(), "What's on (program guide)", "TV guide", "Channel list", "Exit player"}), (d, picked) -> {
                    if (picked < windows.length) { windowPick(windows[picked]); return; }
                    int w = picked - windows.length;
                    if (w == 0) toast(a.toggleFavorite("live", id, title, ext) ? "Added to Favorites" : "Removed from Favorites");
                    else if (w == 1) { if (a.subs != null) a.subs.choose(); }
                    else if (w == 2) a.guide(id, title);
                    else if (w == 3) openGuideFromPlayer();
                    else if (w == 4) channelList();
                    else a.onBackPressed();
                }).show();
    }

    private String subtitlesItem() { return "Subtitles / CC: " + (a.subs != null && a.subs.on() ? "on" : "off"); }

    /** A quick channel picker over the video, so you can jump without leaving the player. */
    private void channelList() {
        if (zapList == null || zapList.length() == 0) { toast("No channel list to show."); return; }
        final JSONArray list = zapList;
        final int playing = zapIndex;
        // Each row: the channel's logo from the playlist and its name (▶ marks the one playing).
        android.widget.ArrayAdapter<String> rows = new android.widget.ArrayAdapter<String>(a, android.R.layout.select_dialog_item) {
            @Override public int getCount() { return list.length(); }
            @Override public String getItem(int i) { JSONObject o = list.optJSONObject(i); return o == null ? "" : o.optString("name"); }
            @Override public View getView(int i, View convert, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(i, convert, parent);
                JSONObject o = list.optJSONObject(i);
                String name = o == null ? "" : o.optString("name");
                t.setText(i == playing ? "▶  " + name : name);
                ChannelIcons.attach(t, o == null ? "" : o.optString("stream_icon"), name, 28);
                return t;
            }
        };
        AlertDialog d = new Dialogs.Builder(a).setTitle("Channels").setAdapter(rows, (dialog, which) -> {
            dialog.dismiss();
            if (which != zapIndex || list != zapList) switchTo(list, which);
        }).create();
        d.show();
        if (zapIndex > 0 && d.getListView() != null) d.getListView().setSelection(zapIndex);
    }

    private void openGuideFromPlayer() {
        JSONArray channels = zapList;
        String streamId = id;
        a.closePlayer();
        a.resumeStreamId = streamId;
        if (channels != null && channels != a.items) { a.items = channels; a.category = ""; }
        a.openGuide(streamId);
    }

    // ---------------------------------------------------------------- list screens

    private boolean listKey(KeyEvent e, int key) {
        if (a.mode.equals("login") && a.getCurrentFocus() instanceof android.widget.EditText) return false;
        switch (key) {
            case KeyEvent.KEYCODE_GUIDE:
                if (a.mode.equals("guide")) return true;
                a.openGuide(null);
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                if (!a.mode.equals("search")) a.searchLive();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                return !a.mode.equals("guide") && jumpFocus(-LIST_JUMP);
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return !a.mode.equals("guide") && jumpFocus(LIST_JUMP);
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Down on the last item goes back to the top, Up on the first goes to the bottom.
                return !a.mode.equals("guide") && FocusWrap.screenKey(a.getWindow().getDecorView(), a.getCurrentFocus(), e);
            case KeyEvent.KEYCODE_MOVE_HOME:
                return jumpFocus(-100_000);
            case KeyEvent.KEYCODE_MOVE_END:
                return jumpFocus(100_000);
            case KeyEvent.KEYCODE_MENU: {
                View f = a.getCurrentFocus();
                if (f != null && f.isLongClickable()) { f.performLongClick(); return true; }
                return false;
            }
            default:
                return false;
        }
    }

    /** Move focus several rows up or down inside the focused list. */
    private boolean jumpFocus(int delta) {
        View f = a.getCurrentFocus();
        if (f == null || !(f.getParent() instanceof LinearLayout)) return false;
        LinearLayout list = (LinearLayout) f.getParent();
        if (list.getOrientation() != LinearLayout.VERTICAL) return false;
        int at = list.indexOfChild(f), n = list.getChildCount();
        int target = RemoteKeys.pageJump(at, delta, n);
        // Skip anything that can't take focus, in the direction of travel.
        int step = delta > 0 ? -1 : 1;
        while (target != at && !list.getChildAt(target).isFocusable()) target += step;
        if (target == at) return true;
        View v = list.getChildAt(target);
        v.requestFocus();
        if (list.getParent() instanceof ScrollView) {
            ScrollView sv = (ScrollView) list.getParent();
            sv.post(() -> sv.smoothScrollTo(0, Math.max(0, v.getTop() - sv.getHeight() / 3)));
        }
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private GradientDrawable panel() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Theme.pane(Theme.alpha(Theme.PANEL,0xe6)), Theme.alpha(Theme.BG, Theme.glass ? 0xb0 : 0xf0)});
        g.setCornerRadius(a.dp(14));
        g.setStroke(a.dp(Theme.glass ? 1 : 2), Theme.glass ? Theme.edge(false) : Theme.STROKE_FOCUS);
        return g;
    }

}
