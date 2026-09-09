package com.mydesk.ai;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra("title");
        String id = intent.getStringExtra("id");
        String repeatRule = intent.getStringExtra("repeatRule");
        if (title == null || title.isEmpty()) title = "확인할 시간이 됐습니다.";
        if (id == null) id = "reminder";
        if (repeatRule == null) repeatRule = "";

        Intent open = new Intent(context, MainActivity.class);
        open.putExtra("reminder_id", id);
        open.putExtra("reminder_title", title);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(context, MainActivity.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("MyDesk AI")
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(title))
                .setAutoCancel(true)
                .setContentIntent(content)
                .setColor(Color.rgb(108, 92, 231));
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id.hashCode(), b.build());

        long next = nextOccurrence(repeatRule);
        if (next > 0) ReminderScheduler.schedule(context, id, title, next, repeatRule);
    }

    private long nextOccurrence(String repeatRule) {
        if (repeatRule == null || repeatRule.isEmpty()) return 0L;
        ZoneId zone = ZoneId.of("Asia/Seoul");
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), zone);
        ZonedDateTime next;
        if ("DAILY".equals(repeatRule)) {
            next = now.plusDays(1);
        } else if ("WEEKLY".equals(repeatRule)) {
            next = now.plusWeeks(1);
        } else if ("WEEKDAYS".equals(repeatRule)) {
            next = now.plusDays(1);
            while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
                next = next.plusDays(1);
            }
        } else {
            return 0L;
        }
        return next.toInstant().toEpochMilli();
    }
}
