package com.mydesk.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReminderSyncJobService extends JobService {
    private static final int JOB_ID = 240901;
    private static final int FAST_SYNC_REQUEST_CODE = 240902;
    private static final long MANUAL_SYNC_MIN_INTERVAL_MS = 10_000L;
    private static final long FAST_SYNC_INTERVAL_MS = 5 * 60 * 1000L;
    private static final AtomicBoolean SYNC_RUNNING = new AtomicBoolean(false);

    public static void schedule(Context context) {
        UpdateChecker.check(context);
        Context app = context.getApplicationContext();
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(app, ReminderSyncJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        }
        scheduleFastSync(app);
    }

    public static void scheduleFastSync(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(app, FastSyncReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                app,
                FAST_SYNC_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long when = System.currentTimeMillis() + FAST_SYNC_INTERVAL_MS;

        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.set(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    public static void syncNow(Context context) {
        Context app = context.getApplicationContext();
        UpdateChecker.check(app);
        SharedPreferences prefs = app.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong("last_manual_sync_request", 0L);
        if (now - last < MANUAL_SYNC_MIN_INTERVAL_MS) return;
        prefs.edit().putLong("last_manual_sync_request", now).apply();
        if (!SYNC_RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { syncContext(app); } catch (Exception ignored) {}
            finally { SYNC_RUNNING.set(false); }
        }).start();
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!SYNC_RUNNING.compareAndSet(false, true)) {
            jobFinished(params, false);
            return false;
        }
        new Thread(() -> {
            try { syncContext(this); } catch (Exception ignored) {}
            finally {
                SYNC_RUNNING.set(false);
                UpdateChecker.check(this);
                scheduleFastSync(this);
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static void syncContext(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String token = prefs.getString("token", "");
        if (token.isEmpty()) return;

        boolean needVoiceCacheBootstrap = !prefs.contains("voice_task_cache");
        String since = needVoiceCacheBootstrap
                ? "1970-01-01T00:00:00.000Z"
                : prefs.getString("server_sync_since", "1970-01-01T00:00:00.000Z");
        String encodedSince = URLEncoder.encode(since, StandardCharsets.UTF_8.name());
        URL url = new URL(MainActivity.APP_URL + "/api/sync?since=" + encodedSince + "&kinds=task");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        if (c.getResponseCode() != 200) {
            c.disconnect();
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        c.disconnect();

        JSONObject root = new JSONObject(sb.toString());
        JSONArray records = root.optJSONArray("records");
        if (records != null) {
            ReminderScheduler.syncTasks(context, records.toString());
            LocalTaskStore.mergeRecords(context, records);
        }

        String serverTime = root.optString("serverTime", "");
        if (!serverTime.isEmpty()) {
            prefs.edit().putString("server_sync_since", serverTime).apply();
        }
    }
}
