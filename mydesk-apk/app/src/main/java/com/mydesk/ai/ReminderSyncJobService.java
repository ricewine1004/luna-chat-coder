package com.mydesk.ai;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ReminderSyncJobService extends JobService {
    private static final int JOB_ID = 240901;

    public static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, ReminderSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    public static void syncNow(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { syncContext(app); } catch (Exception ignored) {}
        }).start();
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { syncContext(this); } catch (Exception ignored) {}
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    private static void syncContext(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String token = prefs.getString("token", "");
        if (token.isEmpty()) return;
        URL url = new URL(MainActivity.APP_URL + "/api/sync?since=1970-01-01T00:00:00.000Z");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        if (c.getResponseCode() != 200) { c.disconnect(); return; }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        c.disconnect();
        JSONObject root = new JSONObject(sb.toString());
        JSONArray records = root.optJSONArray("records");
        if (records != null) ReminderScheduler.syncTasks(context, records.toString());
    }
}
