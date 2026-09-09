package com.mydesk.ai;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends android.app.Activity {
    public static final String APP_URL = "https://mydesk-ai.mydesk-ai.workers.dev";
    public static final String CHANNEL_ID = "mydesk_reminders";
    public static final String PREFS = "mydesk_native";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();
        requestNotificationPermission();
        ReminderSyncJobService.schedule(this);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " MyDeskAI-Android/0.1.0");
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new NativeBridge(), "MyDeskNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { injectNativeBridge(); }
        });
        webView.loadUrl(APP_URL);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "MyDesk AI 알림", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("할 일, 메모, 일정 리마인더");
            channel.enableVibration(true);
            channel.setLightColor(Color.rgb(108, 92, 231));
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }

    private void injectNativeBridge() {
        String js = "(function(){" +
                "if(window.__mydeskNativeInstalled)return;window.__mydeskNativeInstalled=true;" +
                "function sendState(){try{" +
                "var t=localStorage.getItem('mydesk_owner_code')||'';if(t)MyDeskNative.saveToken(t);" +
                "if(typeof state!=='undefined'&&state.records){var a=state.records.filter(function(r){return r&&r.kind==='task'&&r.data&&r.data.dueAt&&r.data.status!=='done'&&!r.deletedAt;});MyDeskNative.syncTasks(JSON.stringify(a));}" +
                "}catch(e){}}" +
                "setInterval(sendState,10000);setTimeout(sendState,1200);" +
                "var f=document.querySelector('#chatForm');if(f){f.addEventListener('submit',function(){try{var i=document.querySelector('#chatInput');if(i&&i.value)MyDeskNative.captureReminderRequest(i.value);}catch(e){}},true);}" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public class NativeBridge {
        @JavascriptInterface public void saveToken(String token) { getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("token", token).apply(); }
        @JavascriptInterface public void syncTasks(String json) { ReminderScheduler.syncTasks(MainActivity.this, json); }
        @JavascriptInterface public void captureReminderRequest(String text) {
            ParsedReminder parsed = parseReminder(text);
            if (parsed != null) {
                ReminderScheduler.schedule(MainActivity.this, "local-" + Math.abs(text.hashCode()) + "-" + parsed.when, parsed.title, parsed.when);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "알림 예약: " + parsed.displayTime, Toast.LENGTH_SHORT).show());
            }
        }
    }

    static class ParsedReminder {
        long when; String title; String displayTime;
        ParsedReminder(long w, String t, String d) { when = w; title = t; displayTime = d; }
    }

    private ParsedReminder parseReminder(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (!(text.contains("알려줘") || text.contains("알림") || text.contains("기억해줘") || text.contains("리마인드"))) return null;
        ZoneId zone = ZoneId.of("Asia/Seoul");
        ZonedDateTime now = ZonedDateTime.now(zone);
        Matcher relMin = Pattern.compile("(\\d+)\\s*분\\s*(?:뒤|후)").matcher(text);
        if (relMin.find()) {
            int mins = Math.min(10080, Integer.parseInt(relMin.group(1)));
            return new ParsedReminder(now.plusMinutes(mins).toInstant().toEpochMilli(), cleanupTitle(text), mins + "분 후");
        }
        Matcher relHour = Pattern.compile("(\\d+)\\s*시간\\s*(?:뒤|후)").matcher(text);
        if (relHour.find()) {
            int hours = Math.min(168, Integer.parseInt(relHour.group(1)));
            return new ParsedReminder(now.plusHours(hours).toInstant().toEpochMilli(), cleanupTitle(text), hours + "시간 후");
        }
        LocalDate date = null;
        if (text.contains("모레")) date = now.toLocalDate().plusDays(2);
        else if (text.contains("내일")) date = now.toLocalDate().plusDays(1);
        else if (text.contains("오늘")) date = now.toLocalDate();
        if (date == null) return null;
        int hour = 9, minute = 0;
        Matcher hm = Pattern.compile("(?:(오전|오후)\\s*)?(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분)?").matcher(text);
        if (hm.find()) {
            String ap = hm.group(1);
            hour = Math.min(23, Integer.parseInt(hm.group(2)));
            if (hm.group(3) != null) minute = Math.min(59, Integer.parseInt(hm.group(3)));
            if ("오후".equals(ap) && hour < 12) hour += 12;
            if ("오전".equals(ap) && hour == 12) hour = 0;
        } else {
            Matcher colon = Pattern.compile("(?:^|\\s)([01]?\\d|2[0-3]):([0-5]\\d)").matcher(text);
            if (colon.find()) { hour = Integer.parseInt(colon.group(1)); minute = Integer.parseInt(colon.group(2)); }
        }
        ZonedDateTime target = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone);
        if (!target.isAfter(now)) target = now.plusMinutes(1);
        String display = target.format(DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN));
        return new ParsedReminder(target.toInstant().toEpochMilli(), cleanupTitle(text), display);
    }

    private String cleanupTitle(String text) {
        String title = text.replaceAll("(알려줘|알림\\s*해줘|기억해줘|리마인드\\s*해줘)", "").trim();
        return title.isEmpty() ? "MyDesk AI 알림" : title;
    }
}
