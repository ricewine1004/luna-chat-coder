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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends android.app.Activity {
    public static final String APP_URL = "https://mydesk-ai.mydesk-ai.workers.dev";
    public static final String CHANNEL_ID = "mydesk_reminders";
    public static final String PREFS = "mydesk_native";
    private WebView webView;
    private String launchReminderId = "";
    private String launchReminderTitle = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchReminderId = getIntent().getStringExtra("reminder_id");
        launchReminderTitle = getIntent().getStringExtra("reminder_title");
        if (launchReminderId == null) launchReminderId = "";
        if (launchReminderTitle == null) launchReminderTitle = "";
        createNotificationChannel();
        requestNotificationPermission();
        ReminderSyncJobService.schedule(this);
        ReminderSyncJobService.syncNow(this);
        UpdateChecker.check(this);

        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " MyDeskAI-Android/0.2.1");
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new NativeBridge(), "MyDeskNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                injectNativeBridge();
                if (!launchReminderTitle.isEmpty()) {
                    Toast.makeText(MainActivity.this, "알림: " + launchReminderTitle, Toast.LENGTH_LONG).show();
                }
            }
        });
        webView.loadUrl(APP_URL);
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        ReminderSyncJobService.syncNow(this);
        UpdateChecker.check(this);
    }

    @Override protected void onPause() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("MyDeskNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
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
        @JavascriptInterface public void saveToken(String token) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("token", token).apply();
            ReminderSyncJobService.syncNow(MainActivity.this);
        }
        @JavascriptInterface public void syncTasks(String json) { ReminderScheduler.syncTasks(MainActivity.this, json); }
        @JavascriptInterface public void captureReminderRequest(String text) {
            ParsedReminder parsed = parseReminder(text);
            if (parsed != null) {
                String id = "local-" + Math.abs(text.hashCode()) + "-" + parsed.when;
                ReminderScheduler.schedule(MainActivity.this, id, parsed.title, parsed.when, parsed.repeatRule);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "알림 예약: " + parsed.displayTime, Toast.LENGTH_SHORT).show());
            }
        }
    }

    static class ParsedReminder {
        long when; String title; String displayTime; String repeatRule;
        ParsedReminder(long w, String t, String d, String r) { when = w; title = t; displayTime = d; repeatRule = r; }
    }

    private ParsedReminder parseReminder(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (!(text.contains("알려줘") || text.contains("알림") || text.contains("기억해줘") || text.contains("리마인드") || text.contains("잊지 않게"))) return null;

        ZoneId zone = ZoneId.of("Asia/Seoul");
        ZonedDateTime now = ZonedDateTime.now(zone);
        String repeatRule = "";
        if (text.contains("매일")) repeatRule = "DAILY";
        else if (text.contains("평일마다") || text.contains("평일 매일") || text.contains("매 평일")) repeatRule = "WEEKDAYS";
        else if (text.contains("매주")) repeatRule = "WEEKLY";

        Matcher relMin = Pattern.compile("(\\d+)\\s*분\\s*(?:뒤|후)").matcher(text);
        if (relMin.find()) {
            int mins = Math.min(10080, Integer.parseInt(relMin.group(1)));
            return new ParsedReminder(now.plusMinutes(mins).toInstant().toEpochMilli(), cleanupTitle(text), mins + "분 후", repeatRule);
        }
        Matcher relHour = Pattern.compile("(\\d+)\\s*시간\\s*(?:뒤|후)").matcher(text);
        if (relHour.find()) {
            int hours = Math.min(168, Integer.parseInt(relHour.group(1)));
            return new ParsedReminder(now.plusHours(hours).toInstant().toEpochMilli(), cleanupTitle(text), hours + "시간 후", repeatRule);
        }

        LocalDate date = null;
        if (text.contains("모레")) date = now.toLocalDate().plusDays(2);
        else if (text.contains("내일")) date = now.toLocalDate().plusDays(1);
        else if (text.contains("오늘")) date = now.toLocalDate();

        Matcher md = Pattern.compile("(?:(\\d{4})\\s*년\\s*)?(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일").matcher(text);
        if (md.find()) {
            int year = md.group(1) != null ? Integer.parseInt(md.group(1)) : now.getYear();
            int month = Integer.parseInt(md.group(2));
            int day = Integer.parseInt(md.group(3));
            try {
                date = LocalDate.of(year, month, day);
                if (md.group(1) == null && date.isBefore(now.toLocalDate())) date = date.plusYears(1);
            } catch (Exception ignored) {}
        }

        DayOfWeek wanted = weekdayFromText(text);
        if (wanted != null) {
            if (text.contains("다음주") || text.contains("다음 주")) {
                LocalDate nextMonday = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                date = nextMonday.with(TemporalAdjusters.nextOrSame(wanted));
            } else {
                date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(wanted));
                if (date.equals(now.toLocalDate()) && !text.contains("오늘")) date = date.plusWeeks(1);
            }
        }

        if (date == null && !repeatRule.isEmpty()) date = now.toLocalDate();
        if (date == null) return null;

        int hour = defaultHourForText(text);
        int minute = 0;
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
        if (!target.isAfter(now)) {
            if ("DAILY".equals(repeatRule) || "WEEKDAYS".equals(repeatRule)) target = target.plusDays(1);
            else if ("WEEKLY".equals(repeatRule)) target = target.plusWeeks(1);
            else target = now.plusMinutes(1);
        }
        if ("WEEKDAYS".equals(repeatRule)) {
            while (target.getDayOfWeek() == DayOfWeek.SATURDAY || target.getDayOfWeek() == DayOfWeek.SUNDAY) target = target.plusDays(1);
        }

        String display = target.format(DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN));
        if (!repeatRule.isEmpty()) display += " 반복";
        return new ParsedReminder(target.toInstant().toEpochMilli(), cleanupTitle(text), display, repeatRule);
    }

    private DayOfWeek weekdayFromText(String text) {
        if (text.contains("월요일")) return DayOfWeek.MONDAY;
        if (text.contains("화요일")) return DayOfWeek.TUESDAY;
        if (text.contains("수요일")) return DayOfWeek.WEDNESDAY;
        if (text.contains("목요일")) return DayOfWeek.THURSDAY;
        if (text.contains("금요일")) return DayOfWeek.FRIDAY;
        if (text.contains("토요일")) return DayOfWeek.SATURDAY;
        if (text.contains("일요일")) return DayOfWeek.SUNDAY;
        return null;
    }

    private int defaultHourForText(String text) {
        if (text.contains("새벽")) return 6;
        if (text.contains("아침")) return 8;
        if (text.contains("점심")) return 12;
        if (text.contains("오후")) return 15;
        if (text.contains("저녁")) return 19;
        if (text.contains("밤")) return 21;
        return 9;
    }

    private String cleanupTitle(String text) {
        String title = text
                .replaceAll("(알려줘|알림\\s*해줘|기억해줘|리마인드\\s*해줘|잊지\\s*않게)", "")
                .replaceAll("(매일|매주|평일마다|평일 매일|매 평일)", "")
                .trim();
        return title.isEmpty() ? "MyDesk AI 알림" : title;
    }
}
