package com.wygtv.tv;

/**
 * Which app this build is. The same code builds two apps: WygTV (IPTV playlists, movies, series and
 * antenna TV) and, when built with WYGOTA=1, WygOTA (only live over-the-air TV from an HDHomeRun tuner:
 * no playlists, movies or series). WygOTA is its own app (com.wygtv.ota) with its own name, icon and releases.
 */
final class Brand {
    /** True in the WygOTA app. */
    static final boolean OTA = BuildConfig.OTA;
    /** True in the Galaxy Store build (GALAXY_STORE=1): the store installs updates, so the in-app updater is off. */
    static final boolean STORE = BuildConfig.STORE;
    /** "WygTV" or "WygOTA", for anything the person reads. */
    static final String NAME = OTA ? "WygOTA" : "WygTV";
    /** Where WygOTA updates from: every release publishes wygota.apk and version.json there. */
    static final String OTA_RELEASE = "https://github.com/wyglaf01/WygOTA/releases/latest/download/";
    static final String OTA_APK = "wygota.apk";

    private Brand() { }
}
