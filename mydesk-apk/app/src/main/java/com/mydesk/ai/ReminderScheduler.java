package com.mydesk.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;

public final class ReminderScheduler {
    private ReminderScheduler() {}

    public static void syncTasks(Context context, String json) {
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r == null) continue;
                JSONObject d = r.optJSONObject("data");
                if (d == null) continue;
                String dueAt = d.optString("dueAt", "");
                if (dueAt.isEmpty()) continue;
                long when;
                try { when = Instant.parse(dueAt).toEpochMilli(); }
                catch (Exception ex) { continue; }
                if (when <= System.currentTimeMillis()) continue;
                String id = r.optString("id", "task-" + i);
                String title = d.optString("title", "MyDesk AI 할 일");
                schedule(context, id, title, when);
            }
        } catch (Exception ignored) {}
    }

    public static void schedule(Context context, String id, String title, long when) {
        int requestCode = id.hashCode();
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("title", title);
        intent.putExtra("id", id);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }
}
