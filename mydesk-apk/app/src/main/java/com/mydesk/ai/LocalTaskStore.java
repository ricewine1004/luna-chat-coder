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
                if (r == null) continue;
                JSONObject d = r.optJSONObject("data");
                if (d == null) continue;
                if (!"task".equalsIgnoreCase(r.optString("kind", ""))) continue;
                if (!r.optString("deletedAt", "").isEmpty()) continue;
                if ("done".equalsIgnoreCase(d.optString("status", ""))) continue;
                clean.put(r);
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
                    JSONObject d = r.optJSONObject("data");
                    boolean remove = !"task".equalsIgnoreCase(r.optString("kind", ""))
                            || !r.optString("deletedAt", "").isEmpty()
                            || d == null
                            || "done".equalsIgnoreCase(d.optString("status", ""));
                    if (remove) map.remove(id); else map.put(id, r);
                }
            }
            JSONArray out = new JSONArray();
            for (JSONObject r : map.values()) out.put(r);
            prefs(context).edit().putString(PREF_KEY, out.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized JSONArray getPendingTasks(Context context) {
        try {
            return new JSONArray(prefs(context).getString(PREF_KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }
}
