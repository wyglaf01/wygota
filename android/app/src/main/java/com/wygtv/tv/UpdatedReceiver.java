package com.wygtv.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android sends this after a new WygTV has been installed over the old one. When the update
 * came from Settings > Check for updates, WygTV opens itself again so the person isn't left
 * on the Home screen. (Some newer Android versions block this; then they open WygTV from Home
 * and see the "Updated to …" notice.)
 */
public final class UpdatedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        if (c.getSharedPreferences("wygtv", Context.MODE_PRIVATE).getInt(Updater.PREF_FROM, -1) < 0) return;
        try {
            Intent open = new Intent(c, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            c.startActivity(open);
        } catch (Exception ignored) { }
    }
}
