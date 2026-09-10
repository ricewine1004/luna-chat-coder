package com.mydesk.ai;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ReminderActionReceiver extends BroadcastReceiver {
    public static final String ACTION_COMPLETE = "com.mydesk.ai.COMPLETE";
    public static final String ACTION_SNOOZE = "com.mydesk.ai.SNOOZE";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String id = intent.getStringExtra("id");
        String title = intent.getStringExtra("title");
        if (id == null || id.isEmpty()) return;
        if (title == null) title = "MyDesk AI 알림";

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id.hashCode());

        if (ACTION_SNOOZE.equals(action)) {
            ReminderScheduler.schedule(context, id, title, System.currentTimeMillis() + 10L * 60L * 1000L, "");
            return;
        }

        if (ACTION_COMPLETE.equals(action)) {
            Context app = context.getApplicationContext();
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try { markDone(app, id); } catch (Exception ignored) {}
                pending.finish();
            }).start();
        }
    }

    private static void markDone(Context context, String id) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString("token", "");
        if (token.isEmpty()) return;

        JSONObject target = LocalTaskStore.findTask(context, id);
        if (target == null) {
            target = fetchTaskOnce(context, token, id);
        }
        if (target == null || !"task".equals(target.optString("kind"))) return;

        JSONObject data = target.optJSONObject("data");
        if (data == null) data = new JSONObject();
        data.put("status", "done");
        String now = java.time.Instant.now().toString();
        target.put("data", data);
        target.put("updatedAt", now);

        JSONObject payload = new JSONObject();
        JSONArray a = new JSONArray();
        a.put(target);
        payload.put("records", a);

        HttpURLConnection u = (HttpURLConnection) new URL(MainActivity.APP_URL + "/api/records/upsert").openConnection();
        u.setConnectTimeout(10000);
        u.setReadTimeout(10000);
        u.setRequestMethod("POST");
        u.setDoOutput(true);
        u.setRequestProperty("Authorization", "Bearer " + token);
        u.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = u.getOutputStream()) { os.write(body); }
        int status = u.getResponseCode();
        u.disconnect();

        if (status >= 200 && status < 300) {
            LocalTaskStore.removeTask(context, id);
            ReminderScheduler.cancel(context, id);
        }
    }

    private static JSONObject fetchTaskOnce(Context context, String token, String id) {
        try {
            URL syncUrl = new URL(MainActivity.APP_URL + "/api/sync?since=1970-01-01T00:00:00.000Z");
            HttpURLConnection c = (HttpURLConnection) syncUrl.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            c.disconnect();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray records = root.optJSONArray("records");
            if (records == null) return null;
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r != null && id.equals(r.optString("id"))) return r;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
