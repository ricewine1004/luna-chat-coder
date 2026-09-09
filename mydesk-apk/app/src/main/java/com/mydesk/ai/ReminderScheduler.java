package com.mydesk.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class ReminderScheduler {
    private ReminderScheduler() {}

    public static void syncTasks(Context context, String json) {
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r == null) continue;
                String id = r.optString("id", "task-" + i);
                JSONObject d = r.optJSONObject("data");
                if (d == null) continue;

                boolean deleted = !r.optString("deletedAt", "").isEmpty();
                boolean done = "done".equalsIgnoreCase(d.optString("status", ""));
                if (deleted || done) {
                    cancel(context, id);
                    continue;
                }

                String dueAt = d.optString("dueAt", "");
                if (dueAt.isEmpty()) continue;
                long when = parseDueAt(dueAt);
                if (when <= System.currentTimeMillis()) continue;

                String title = d.optString("title", "MyDesk AI 할 일");
                String repeatRule = d.optString("repeatRule", "");
                schedule(context, id, title, when, repeatRule);
            }
        } catch (Exception ignored) {}
    }

    private static long parseDueAt(String dueAt) {
        if (dueAt == null || dueAt.trim().isEmpty()) return 0L;
        String raw = dueAt.trim();
        try { return Instant.parse(raw).toEpochMilli(); }
        catch (Exception ignored) {}

        ZoneId zone = ZoneId.of("Asia/Seoul");
        try {
            LocalDateTime dt = LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return dt.atZone(zone).toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        try {
            LocalDate date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
            return ZonedDateTime.of(date.atTime(9, 0), zone).toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        return 0L;
    }

    public static void schedule(Context context, String id, String title, long when) {
        schedule(context, id, title, when, "");
    }

    public static void schedule(Context context, String id, String title, long when, String repeatRule) {
        int requestCode = id.hashCode();
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("title", title);
        intent.putExtra("id", id);
        intent.putExtra("repeatRule", repeatRule == null ? "" : repeatRule);
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

    public static void cancel(Context context, String id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, id.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }
}
