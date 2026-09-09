package com.mydesk.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ReminderReceiver extends BroadcastReceiver {
    private static final String SOUND_CHANNEL_ID = "mydesk_reminders_sound_v1";

    @Override public void onReceive(Context context, Intent intent) {
        ensureSoundChannel(context);

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

        Intent completeIntent = new Intent(context, ReminderActionReceiver.class);
        completeIntent.setAction(ReminderActionReceiver.ACTION_COMPLETE);
        completeIntent.putExtra("id", id);
        completeIntent.putExtra("title", title);
        PendingIntent complete = PendingIntent.getBroadcast(context, id.hashCode() ^ 0x3311, completeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent snoozeIntent = new Intent(context, ReminderActionReceiver.class);
        snoozeIntent.setAction(ReminderActionReceiver.ACTION_SNOOZE);
        snoozeIntent.putExtra("id", id);
        snoozeIntent.putExtra("title", title);
        PendingIntent snooze = PendingIntent.getBroadcast(context, id.hashCode() ^ 0x7722, snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, SOUND_CHANNEL_ID)
                : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("MyDesk AI")
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(title))
                .setAutoCancel(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "완료", complete).build())
                .addAction(new Notification.Action.Builder(null, "10분 미루기", snooze).build())
                .setColor(Color.rgb(108, 92, 231));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName() + "/" + R.raw.mydesk_notify);
            b.setSound(soundUri);
            b.setVibrate(new long[]{0, 180, 90, 180});
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id.hashCode(), b.build());

        long next = nextOccurrence(repeatRule);
        if (next > 0) ReminderScheduler.schedule(context, id, title, next, repeatRule);
    }

    private void ensureSoundChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(SOUND_CHANNEL_ID) != null) return;

        Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName() + "/" + R.raw.mydesk_notify);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel channel = new NotificationChannel(
                SOUND_CHANNEL_ID,
                "MyDesk AI 알림음",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("MyDesk AI 일정, 할 일, 미리 알림");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 180, 90, 180});
        channel.setLightColor(Color.rgb(108, 92, 231));
        channel.setSound(soundUri, attributes);
        nm.createNotificationChannel(channel);
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
