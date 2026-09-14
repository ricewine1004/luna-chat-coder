package com.mydesk.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public final class ReminderScheduler {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private ReminderScheduler() {}

    public static void syncTasks(Context context, String json) {
        try {
            JSONArray a = new JSONArray(json);
            long now = System.currentTimeMillis();

            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r == null) continue;

                String id = r.optString("id", "task-" + i);
                JSONObject d = r.optJSONObject("data");
                if (d == null) {
                    cancelAll(context, id);
                    continue;
                }

                boolean deleted = !r.optString("deletedAt", "").isEmpty();
                boolean done = "done".equalsIgnoreCase(d.optString("status", ""));
                if (deleted || done) {
                    cancelAll(context, id);
                    continue;
                }

                long baseWhen = parseDueAt(d.optString("dueAt", ""));
                if (baseWhen <= 0L) {
                    cancelAll(context, id);
                    continue;
                }

                String repeatRule = normalizeRepeatRule(d.optString("repeatRule", ""));
                int repeatDays = clampRepeatDays(repeatRule, d.optInt("repeatDays", 0));
                int remindBeforeMinutes = Math.max(0, Math.min(10080, d.optInt("remindBeforeMinutes", 0)));

                long nextWhen;
                if (baseWhen > now) {
                    nextWhen = baseWhen;
                } else if (!repeatRule.isEmpty()) {
                    nextWhen = nextOccurrenceAfter(baseWhen, repeatRule, repeatDays, now);
                } else {
                    nextWhen = 0L;
                }

                if (nextWhen <= 0L) {
                    cancelAll(context, id);
                    continue;
                }

                String title = d.optString("title", "MyDesk AI 할 일");
                scheduleOccurrence(
                        context,
                        id,
                        title,
                        baseWhen,
                        nextWhen,
                        repeatRule,
                        repeatDays,
                        remindBeforeMinutes
                );
            }
        } catch (Exception ignored) {}
    }

    public static void schedule(Context context, String id, String title, long when) {
        schedule(context, id, title, when, "", 0);
    }

    public static void schedule(Context context, String id, String title, long when, String repeatRule) {
        schedule(context, id, title, when, repeatRule, 0);
    }

    public static void schedule(Context context, String id, String title, long when, String repeatRule, int repeatDays) {
        String normalized = normalizeRepeatRule(repeatRule);
        scheduleOccurrence(
                context,
                id,
                title,
                when,
                when,
                normalized,
                clampRepeatDays(normalized, repeatDays),
                0
        );
    }

    public static void scheduleSnooze(Context context, String id, String title, long when) {
        scheduleInternal(
                context,
                id + "-snooze",
                id,
                title,
                when,
                "",
                0,
                0L,
                0
        );
    }

    public static void scheduleRecurringNext(
            Context context,
            String id,
            String title,
            long baseWhen,
            String repeatRule,
            int repeatDays,
            int remindBeforeMinutes,
            long afterMs
    ) {
        String normalized = normalizeRepeatRule(repeatRule);
        if (normalized.isEmpty() || baseWhen <= 0L) return;

        long next = nextOccurrenceAfter(baseWhen, normalized, repeatDays, afterMs);
        if (next <= 0L) {
            cancelAll(context, id);
            return;
        }

        scheduleOccurrence(
                context,
                id,
                title,
                baseWhen,
                next,
                normalized,
                clampRepeatDays(normalized, repeatDays),
                Math.max(0, Math.min(10080, remindBeforeMinutes))
        );
    }

    private static void scheduleOccurrence(
            Context context,
            String id,
            String title,
            long baseWhen,
            long occurrenceWhen,
            String repeatRule,
            int repeatDays,
            int remindBeforeMinutes
    ) {
        if (occurrenceWhen <= System.currentTimeMillis()) return;

        if (remindBeforeMinutes > 0) {
            long preWhen = occurrenceWhen - remindBeforeMinutes * 60_000L;
            if (preWhen > System.currentTimeMillis()) {
                scheduleInternal(
                        context,
                        id + "-pre",
                        id,
                        "미리 알림 · " + title,
                        preWhen,
                        "",
                        0,
                        0L,
                        0
                );
            } else {
                cancelAlarmKey(context, id + "-pre");
            }
        } else {
            cancelAlarmKey(context, id + "-pre");
        }

        scheduleInternal(
                context,
                id,
                id,
                title,
                occurrenceWhen,
                repeatRule,
                repeatDays,
                baseWhen,
                remindBeforeMinutes
        );
    }

    private static void scheduleInternal(
            Context context,
            String alarmKey,
            String taskId,
            String title,
            long when,
            String repeatRule,
            int repeatDays,
            long baseWhen,
            int remindBeforeMinutes
    ) {
        int requestCode = alarmKey.hashCode();

        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("title", title);
        intent.putExtra("id", taskId);
        intent.putExtra("repeatRule", repeatRule == null ? "" : repeatRule);
        intent.putExtra("repeatDays", repeatDays);
        intent.putExtra("baseWhen", baseWhen);
        intent.putExtra("remindBeforeMinutes", remindBeforeMinutes);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

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
        cancelAll(context, id);
    }

    public static void cancelAll(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        cancelAlarmKey(context, id);
        cancelAlarmKey(context, id + "-pre");
        cancelAlarmKey(context, id + "-snooze");
    }

    private static void cancelAlarmKey(Context context, String alarmKey) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                alarmKey.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );

        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    public static long nextOccurrenceAfter(
            long baseWhen,
            String repeatRule,
            int repeatDays,
            long afterMs
    ) {
        String rule = normalizeRepeatRule(repeatRule);
        if (rule.isEmpty() || baseWhen <= 0L) return 0L;

        ZonedDateTime base = Instant.ofEpochMilli(baseWhen).atZone(KST);
        ZonedDateTime after = Instant.ofEpochMilli(afterMs).atZone(KST);

        if ("DAILY".equals(rule) || "DAILY_LIMITED".equals(rule)) {
            long days = Math.max(0L, ChronoUnit.DAYS.between(base.toLocalDate(), after.toLocalDate()));
            ZonedDateTime candidate = base.plusDays(days);
            if (!candidate.toInstant().isAfter(after.toInstant())) {
                days++;
                candidate = base.plusDays(days);
            }

            if ("DAILY_LIMITED".equals(rule)) {
                int max = clampRepeatDays(rule, repeatDays);
                long occurrenceIndex = days + 1L;
                if (occurrenceIndex > max) return 0L;
            }
            return candidate.toInstant().toEpochMilli();
        }

        if ("WEEKLY".equals(rule)) {
            long weeks = Math.max(0L, ChronoUnit.WEEKS.between(base.toLocalDate(), after.toLocalDate()));
            ZonedDateTime candidate = base.plusWeeks(weeks);
            if (!candidate.toInstant().isAfter(after.toInstant())) candidate = candidate.plusWeeks(1);
            return candidate.toInstant().toEpochMilli();
        }

        if ("WEEKDAYS".equals(rule)) {
            ZonedDateTime candidate = ZonedDateTime.of(
                    after.toLocalDate(),
                    base.toLocalTime(),
                    KST
            );
            if (!candidate.toInstant().isAfter(after.toInstant()) || !candidate.toInstant().isAfter(base.toInstant())) {
                candidate = candidate.plusDays(1);
            }
            while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                    || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                candidate = candidate.plusDays(1);
            }
            return candidate.toInstant().toEpochMilli();
        }

        return 0L;
    }

    private static String normalizeRepeatRule(String value) {
        String rule = value == null ? "" : value.trim().toUpperCase();
        if ("DAILY".equals(rule)
                || "WEEKDAYS".equals(rule)
                || "WEEKLY".equals(rule)
                || "DAILY_LIMITED".equals(rule)) {
            return rule;
        }
        return "";
    }

    private static int clampRepeatDays(String repeatRule, int repeatDays) {
        if (!"DAILY_LIMITED".equals(normalizeRepeatRule(repeatRule))) return 0;
        return Math.max(2, Math.min(7, repeatDays <= 0 ? 7 : repeatDays));
    }

    private static long parseDueAt(String dueAt) {
        if (dueAt == null || dueAt.trim().isEmpty()) return 0L;
        String raw = dueAt.trim();

        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (Exception ignored) {}

        try {
            LocalDateTime dt = LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return dt.atZone(KST).toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        try {
            LocalDate date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
            return ZonedDateTime.of(date.atTime(9, 0), KST).toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        return 0L;
    }
}
