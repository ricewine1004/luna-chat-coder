package com.mydesk.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class FastSyncReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ReminderSyncJobService.syncNow(context);
        ReminderSyncJobService.scheduleFastSync(context);
    }
}
