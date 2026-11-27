package com.wygtv.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Gets the system installer's answer for an in-app update: shows its confirmation screen, or says why it failed. */
public final class UpdateResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); return; } catch (Exception ignored) { }
            }
            status = PackageInstaller.STATUS_FAILURE;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        Toast.makeText(c, Updater.failureText(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)), Toast.LENGTH_LONG).show();
    }
}
