package com.mydesk.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LocalTaskStore {
    private static final String PREF_KEY = "voice_task_cache";

    private LocalTaskStore() {}

    public static synchronized void replacePendingTasks(Context context, String json) {
        try {
            JSONArray input = new JSONArray(json == null ? "[]" : json);
            JSONArray clean = new JSONArray();
            for (int i = 0; i < input.length(); i++) {
                JSONObject r = input.optJSONObject(i);
                if (isPendingTask(r)) clean.put(r);
            }
            prefs(context).edit().putString(PREF_KEY, clean.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized void mergeRecords(Context context, JSONArray records) {
        try {
            Map<String, JSONObject> map = new LinkedHashMap<>();
            JSONArray old = getPendingTasks(context);
            for (int i = 0; i < old.length(); i++) {
                JSONObject r = old.optJSONObject(i);
                if (r != null) map.put(r.optString("id", "old-" + i), r);
            }
            if (records != null) {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject r = records.optJSONObject(i);
                    if (r == null) continue;
                    String id = r.optString("id", "");
                    if (id.isEmpty()) continue;
                    if (isPendingTask(r)) map.put(id, r); else map.remove(id);
                }
            }
            saveMap(context, map);
        } catch (Exception ignored) {}
    }

    public static synchronized JSONArray getPendingTasks(Context context) {
        try {
            return new JSONArray(prefs(context).getString(PREF_KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    public static synchronized JSONObject findTask(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray a = getPendingTasks(context);
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r != null && id.equals(r.optString("id", ""))) return r;
        }
        return null;
    }

    public static synchronized void removeTask(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        try {
            Map<String, JSONObject> map = new LinkedHashMap<>();
            JSONArray old = getPendingTasks(context);
            for (int i = 0; i < old.length(); i++) {
                JSONObject r = old.optJSONObject(i);
                if (r == null) continue;
                String key = r.optString("id", "old-" + i);
                if (!id.equals(key)) map.put(key, r);
            }
            saveMap(context, map);
        } catch (Exception ignored) {}
    }

    public static synchronized void updateTask(Context context, JSONObject record) {
        if (record == null) return;
        String id = record.optString("id", "");
        if (id.isEmpty()) return;
        try {
            Map<String, JSONObject> map = new LinkedHashMap<>();
            JSONArray old = getPendingTasks(context);
            for (int i = 0; i < old.length(); i++) {
                JSONObject r = old.optJSONObject(i);
                if (r != null) map.put(r.optString("id", "old-" + i), r);
            }
            if (isPendingTask(record)) map.put(id, record); else map.remove(id);
            saveMap(context, map);
        } catch (Exception ignored) {}
    }

    private static boolean isPendingTask(JSONObject r) {
        if (r == null) return false;
        JSONObject d = r.optJSONObject("data");
        if (d == null) return false;
        if (!"task".equalsIgnoreCase(r.optString("kind", ""))) return false;
        if (!r.optString("deletedAt", "").isEmpty()) return false;
        return !"done".equalsIgnoreCase(d.optString("status", ""));
    }

    private static void saveMap(Context context, Map<String, JSONObject> map) {
        JSONArray out = new JSONArray();
        for (JSONObject r : map.values()) out.put(r);
        prefs(context).edit().putString(PREF_KEY, out.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }
}
