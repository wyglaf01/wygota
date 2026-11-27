package com.wygtv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import android.net.Uri;

import java.io.IOException;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

/**
 * Builds every player in the app. Over-the-air TV (HDHomeRun) is MPEG-TS with MPEG-2 video and Dolby
 * AC-3 audio; many tablets and some TV boxes have no AC-3 decoder, so the FFmpeg audio extension
 * (AC-3, E-AC-3, DTS, TrueHD and more) is added. It is only a fallback: the device's own decoders still
 * win when they have one, so HDMI passthrough to a soundbar keeps working. FFmpeg steps in when the
 * device has no decoder for a track, or (see {@link #useFfmpegAudio}) when the device's audio decoder
 * fails while playing.
 * The renderer settings are Media3 "unstable" API, which is opted into here.
 */
@OptIn(markerClass = UnstableApi.class)
final class Players {
    private Players() { }

    /** Settings: how many seconds to load before a stream starts (and after it runs dry) for the main player. */
    static final String PREF_BUFFER = "play_buffer";
    static final String[] BUFFER_KEYS = {"off", "small", "medium", "large"};
    static final String[] BUFFER_LABELS = {"Off  •  starts fastest", "Small  •  5 seconds (recommended)", "Medium  •  10 seconds", "Large  •  20 seconds"};
    static final String BUFFER_DEFAULT = "small";

    /** Settings: which video decoders to try first. */
    static final String PREF_DECODER = "video_decoder";
    static final String[] DECODER_KEYS = {"auto", "hardware", "software"};
    static final String[] DECODER_LABELS = {"Automatic (device default)", "Hardware first", "Software first (recommended)"};
    static final String DECODER_DEFAULT = "software";

    /** Seconds loaded before playback starts for a buffer choice; 0 keeps Media3's own 2.5 s start. */
    static int bufferSeconds(String key) {
        switch (key == null ? "" : key) {
            case "off": return 0;
            case "medium": return 10;
            case "large": return 20;
            default: return 5;
        }
    }

    /** {min buffer, max buffer, needed to start, needed to restart after running dry}, all in ms. */
    static int[] bufferMs(int seconds) {
        if (seconds <= 0) return new int[] {
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS };
        int start = seconds * 1000;
        int min = Math.max(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, start * 4);
        int max = Math.max(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, min + 20_000);
        return new int[] { min, max, start, Math.max(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, start * 2) };
    }

    /** The guide preview: quick start, no extra buffer. */
    static ExoPlayer create(Context c) { return create(c, 0); }

    /** The full-screen player, with the buffer and decoder chosen in Settings. */
    static ExoPlayer create(Context c, SharedPreferences prefs) {
        return create(c, bufferSeconds(prefs.getString(PREF_BUFFER, BUFFER_DEFAULT)), prefs.getString(PREF_DECODER, DECODER_DEFAULT));
    }

    private static ExoPlayer create(Context c, int bufferSeconds) {
        return create(c, bufferSeconds, c.getSharedPreferences("wygtv", Context.MODE_PRIVATE).getString(PREF_DECODER, DECODER_DEFAULT));
    }

    private static ExoPlayer create(Context c, int bufferSeconds, String decoder) {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(c)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecs(decoder));
        // Many IPTV servers redirect from http to https (or the other way); allow it, and wait a bit longer on slow servers.
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(15_000);
        int[] b = bufferMs(bufferSeconds);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(b[0], b[1], b[2], b[3])
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        return new ExoPlayer.Builder(c, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(tabloResolving(new DefaultDataSource.Factory(c, http)), liveExtractors()))
                .setLoadControl(load)
                .build();
    }

    /**
     * A legacy Tablo channel's playlist link is a stand-in (…/guide/channels/ID/watch.m3u8): as the player opens it, ask
     * the Tablo to tune the channel and read its real HLS link instead. Every other link passes through unchanged.
     */
    static DataSource.Factory tabloResolving(DataSource.Factory upstream) {
        return new ResolvingDataSource.Factory(upstream, spec -> {
            String u = spec.uri.toString();
            if (!Tablo.isWatchUrl(u)) return spec;
            try { return spec.withUri(Uri.parse(Tablo.resolveWatch(u))); }
            catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException(new TabloTuneException(e)); }
        });
    }

    /** Tuning a Tablo channel failed; its message is shown to the person. */
    static final class TabloTuneException extends Exception {
        TabloTuneException(Exception cause) {
            super(cause instanceof Tablo.LockedException ? Tablo.LOCKED
                    : "The Tablo couldn't tune this channel (" + cause.getMessage() + "). All of its tuners may be in use, or it may be off.", cause);
        }
    }

    /**
     * How MPEG-TS live streams are read, set the way IPTV players commonly do: find each video frame by itself (many
     * IPTV and antenna streams don't mark frame boundaries) and accept streams that start without a full key frame.
     * Closed captions (CEA-608/708) ride inside those video frames, so reading every frame keeps them coming.
     */
    static androidx.media3.extractor.DefaultExtractorsFactory liveExtractors() {
        return new androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                        | androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES);
    }

    /** Errors from a decoder or the audio output: the ones FFmpeg can get around when it's the audio part that failed. */
    static boolean decoderError(int errorCode) {
        return (errorCode >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && errorCode <= PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                || errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED;
    }

    /**
     * When the device's own audio decoder fails, turns it off for this player so the FFmpeg audio decoder
     * takes over on the next try. Returns false when the error wasn't the device's audio decoder, or FFmpeg
     * is already in use (so there's nothing left to fall back to here).
     */
    static boolean useFfmpegAudio(ExoPlayer p, PlaybackException error) {
        if (!(error instanceof ExoPlaybackException) || !decoderError(error.errorCode)) return false;
        ExoPlaybackException e = (ExoPlaybackException) error;
        if (e.type != ExoPlaybackException.TYPE_RENDERER || e.rendererIndex < 0 || e.rendererIndex >= p.getRendererCount()) return false;
        if (p.getRendererType(e.rendererIndex) != C.TRACK_TYPE_AUDIO) return false;
        if (e.rendererName != null && e.rendererName.toLowerCase(java.util.Locale.US).contains("ffmpeg")) return false;
        boolean haveFfmpeg = false;
        for (int i = 0; i < p.getRendererCount(); i++)
            if (i != e.rendererIndex && p.getRendererType(i) == C.TRACK_TYPE_AUDIO
                    && p.getRenderer(i).getName().toLowerCase(java.util.Locale.US).contains("ffmpeg")) haveFfmpeg = true;
        if (!haveFfmpeg || !(p.getTrackSelector() instanceof DefaultTrackSelector)) return false;
        DefaultTrackSelector ts = (DefaultTrackSelector) p.getTrackSelector();
        if (ts.getParameters().getRendererDisabled(e.rendererIndex)) return false;
        ts.setParameters(ts.buildUponParameters().setRendererDisabled(e.rendererIndex, true));
        return true;
    }

    /** Puts hardware or software video decoders first; audio and "auto" keep the device's own order. */
    static MediaCodecSelector codecs(String decoder) {
        if (!"hardware".equals(decoder) && !"software".equals(decoder)) return MediaCodecSelector.DEFAULT;
        final boolean software = "software".equals(decoder);
        return (mimeType, secure, tunneling) -> {
            List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
            if (!MimeTypes.isVideo(mimeType)) return all;
            List<MediaCodecInfo> first = new ArrayList<>(), rest = new ArrayList<>();
            for (MediaCodecInfo info : all) (info.softwareOnly == software ? first : rest).add(info);
            first.addAll(rest);
            return first;
        };
    }

    /** Tells the viewer when this device can't decode an HDHomeRun channel's video. Errors are explained by {@link #tunerMessage} once {@link Recovery} gives up. */
    static void explainTunerProblems(Context c, ExoPlayer player) {
        player.addListener(new Player.Listener() {
            boolean toldVideo;

            @Override public void onTracksChanged(Tracks tracks) {
                if (toldVideo || tracks.isEmpty()) return;
                boolean hasVideo = false, canPlayVideo = false;
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
                    hasVideo = true;
                    if (g.isSupported()) canPlayVideo = true;
                }
                if (hasVideo && !canPlayVideo) {
                    toldVideo = true;
                    Toast.makeText(c, "This device can't decode this channel's video (usually MPEG-2). Sound only. "
                            + "Most Android TV boxes can; many tablets can't.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    static String tunerMessage(PlaybackException error) {
        for (Throwable x = error; x != null; x = x.getCause()) if (x instanceof TabloTuneException) return x.getMessage();
        Throwable t = error;
        while (t != null && !(t instanceof HttpDataSource.InvalidResponseCodeException)) t = t.getCause();
        if (t != null) {
            int code = ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            if (code == 503) return "All of the HDHomeRun's tuners are in use (by another TV, the guide preview or a recording). Stop one and try again.";
            if (code == 404) return "The tuner doesn't have this channel any more. Refresh the tuner from Playlists.";
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
            return "Couldn't reach the HDHomeRun. Check it's on and on the same network.";
        return "This channel didn't play. The signal may be too weak right now.";
    }
}
