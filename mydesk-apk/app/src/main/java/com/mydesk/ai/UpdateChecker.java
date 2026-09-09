package com.mydesk.ai;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class UpdateChecker {
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/ricewine1004/luna-chat-coder/mydesk-updates/mydesk-updates/latest.json";
    private static final long INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private UpdateChecker() {}

    public static void check(Context context) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        long last = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getLong("last_update_check", 0L);
        if (now - last < INTERVAL_MS) return;
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putLong("last_update_check", now).apply();
        new Thread(() -> runCheck(app)).start();
    }

    private static void runCheck(Context context) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) { c.disconnect(); return; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            c.disconnect();
            JSONObject j = new JSONObject(sb.toString());
            int versionCode = j.optInt("versionCode", 0);
            if (versionCode <= currentVersionCode(context)) return;
            String versionName = j.optString("versionName", "새 버전");
            String apkUrl = j.optString("apkUrl", "");
            String notes = j.optString("notes", "새 버전을 설치할 수 있습니다.");
            if (apkUrl.isEmpty()) return;

            Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
            PendingIntent pi = PendingIntent.getActivity(context, 92001, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(context, MainActivity.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("MyDesk AI 업데이트 " + versionName)
                    .setContentText(notes)
                    .setStyle(new Notification.BigTextStyle().bigText(notes + "\n눌러서 업데이트 파일을 받으세요."))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setColor(Color.rgb(108, 92, 231));
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(92001, b.build());
        } catch (Exception ignored) {}
    }

    private static long currentVersionCode(Context context) throws Exception {
        PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }
}
