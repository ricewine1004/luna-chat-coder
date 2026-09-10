package com.mydesk.ai;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class VoiceAssistantManager {
    private static final String ACK_ID = "wake_ack";
    private static final String CONTINUE_PREFIX = "continue_";
    private final VoiceAssistantActivity activity;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private boolean ttsReady = false;
    private boolean startWhenReady = false;
    private volatile boolean destroyed = false;
    private String lastSpoken = "";
    private String currentTaskId = "";

    private SmsVoiceHelper.SmsRequest pendingSmsRequest;
    private boolean awaitingSmsConfirmation = false;
    private String pendingSmsDisplayName = "";
    private String pendingSmsPhone = "";
    private String pendingSmsMessage = "";

    public VoiceAssistantManager(VoiceAssistantActivity activity) {
        this.activity = activity;
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(activity.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                activity.setStatus("음성 안내를 시작할 수 없습니다.");
                return;
            }
            tts.setLanguage(Locale.KOREA);
            tts.setSpeechRate(0.88f);
            tts.setPitch(1.0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            chooseBestKoreanVoice();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}

                @Override public void onError(String utteranceId) {
                    activity.runOnUiThread(() -> activity.setStatus("음성 안내 중 오류가 발생했습니다."));
                }

                @Override public void onDone(String utteranceId) {
                    if (destroyed) return;
                    if (ACK_ID.equals(utteranceId)
                            || (utteranceId != null && utteranceId.startsWith(CONTINUE_PREFIX))) {
                        activity.runOnUiThread(VoiceAssistantManager.this::startListening);
                    }
                }
            });
            ttsReady = true;
            if (startWhenReady) activity.runOnUiThread(VoiceAssistantManager.this::start);
        });
    }

    private void chooseBestKoreanVoice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || tts == null || tts.getVoices() == null) return;
        List<Voice> candidates = new ArrayList<>();
        for (Voice v : tts.getVoices()) {
            Locale l = v.getLocale();
            if (l != null && "ko".equalsIgnoreCase(l.getLanguage())) candidates.add(v);
        }
        candidates.sort(Comparator
                .comparing((Voice v) -> v.isNetworkConnectionRequired())
                .thenComparing(Comparator.comparingInt(Voice::getQuality).reversed())
                .thenComparingInt(Voice::getLatency));
        if (!candidates.isEmpty()) tts.setVoice(candidates.get(0));
    }

    public void start() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, VoiceAssistantActivity.REQ_RECORD_AUDIO);
            return;
        }
        if (!ttsReady) {
            startWhenReady = true;
            return;
        }
        startWhenReady = false;
        activity.setStatus("말씀해 주세요");
        speak("네. 말씀하세요.", ACK_ID, false);
    }

    public void onMicrophonePermissionResult(boolean granted) {
        if (granted) start();
        else {
            activity.setStatus("마이크 권한이 필요합니다.");
            speak("마이크 권한이 필요합니다.", "mic_denied", true);
        }
    }

    public void onSmsPermissionsResult(boolean granted) {
        if (destroyed) return;
        if (!granted) {
            clearPendingSms();
            speak("문자를 보내려면 연락처와 문자 권한이 필요합니다. 설정에서 권한을 허용해 주세요.",
                    CONTINUE_PREFIX + "sms_permission_denied", true);
            return;
        }
        resolvePendingSmsRequest();
    }

    private void startListening() {
        if (destroyed) return;
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            speak("음성 인식 기능을 사용할 수 없습니다.", "recognizer_missing", true);
            return;
        }
        destroyRecognizer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
        } else {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        }
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { activity.setStatus("듣고 있어요…"); }
            @Override public void onBeginningOfSpeech() { activity.setStatus("듣고 있어요…"); }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { activity.setStatus("확인하고 있어요…"); }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override public void onError(int error) {
                destroyRecognizer();
                if (destroyed) return;
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    speak("잘 듣지 못했어요. 다시 말씀해 주세요.", CONTINUE_PREFIX + "retry", true);
                } else {
                    speak("음성 인식을 다시 시도할게요.", CONTINUE_PREFIX + "error", true);
                }
            }

            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = list == null || list.isEmpty() ? "" : list.get(0).trim();
                destroyRecognizer();
                activity.setStatus(text.isEmpty() ? "잘 듣지 못했어요" : "“" + text + "”");
                handleCommand(text);
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L);
        recognizer.startListening(intent);
    }

    private void handleCommand(String text) {
        if (text == null || text.isEmpty()) {
            speak("잘 듣지 못했어요. 다시 말씀해 주세요.", CONTINUE_PREFIX + "empty", true);
            return;
        }

        String compact = text.replace(" ", "");

        if (compact.contains("종료") || compact.contains("그만") || compact.contains("끝내")) {
            clearPendingSms();
            speak("네. 음성 비서를 종료할게요.", "goodbye", true);
            activity.finishAfterDelay(1400);
            return;
        }

        if (awaitingSmsConfirmation) {
            handleSmsConfirmation(text);
            return;
        }

        SmsVoiceHelper.SmsRequest smsRequest = SmsVoiceHelper.parse(text);
        if (smsRequest != null) {
            beginSmsRequest(smsRequest);
            return;
        }

        if (compact.contains("다시말해") || compact.contains("다시읽어") || compact.contains("한번더")) {
            String repeat = lastSpoken.isEmpty() ? "아직 다시 읽을 내용이 없습니다." : lastSpoken;
            speak(repeat, CONTINUE_PREFIX + "repeat", false);
            return;
        }

        if ((compact.contains("오늘") && (compact.contains("알림") || compact.contains("일정") || compact.contains("할일") || compact.contains("브리핑")))
                || compact.contains("오늘뭐있어") || compact.contains("오늘뭐해야")) {
            speak(buildBriefingForDate(LocalDate.now(ZoneId.of("Asia/Seoul")), "오늘"), CONTINUE_PREFIX + "today", true);
            return;
        }

        if (compact.contains("내일") && (compact.contains("알림") || compact.contains("일정") || compact.contains("할일"))) {
            speak(buildBriefingForDate(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1), "내일"), CONTINUE_PREFIX + "tomorrow", true);
            return;
        }

        if (compact.contains("미완료") || compact.contains("밀린일") || compact.contains("지난일정") || compact.contains("못끝낸")) {
            speak(buildOverdueBriefing(), CONTINUE_PREFIX + "overdue", true);
            return;
        }

        if (compact.contains("다음일정") || compact.contains("다음할일") || compact.equals("다음") || compact.contains("다음거")) {
            Item next = findNextTask();
            if (next == null) {
                clearCurrentTask();
                speak("앞으로 예정된 일정이 없습니다.", CONTINUE_PREFIX + "next_none", true);
            } else {
                setCurrentTask(next);
                speak(formatTask(next, "다음 일정은 "), CONTINUE_PREFIX + "next", true);
            }
            return;
        }

        if ((compact.contains("몇개") || compact.contains("몇건") || compact.contains("몇개남"))
                && (compact.contains("일정") || compact.contains("할일") || compact.contains("남"))) {
            int count = LocalTaskStore.getPendingTasks(activity).length();
            speak(count == 0 ? "남아 있는 할 일이 없습니다." : "현재 남아 있는 할 일은 " + count + "건입니다.",
                    CONTINUE_PREFIX + "count", true);
            return;
        }

        if (compact.contains("완료처리") || compact.equals("완료") || compact.contains("끝냈어") || compact.contains("끝났어")) {
            Item target = resolveCurrentOrNextTask();
            if (target == null) {
                speak("완료 처리할 일정이 없습니다.", CONTINUE_PREFIX + "complete_none", true);
            } else {
                sendComplete(target);
                speak(target.title + "을 완료 처리할게요.", CONTINUE_PREFIX + "complete", true);
                clearCurrentTask();
            }
            return;
        }

        if ((compact.contains("10분") || compact.contains("십분"))
                && (compact.contains("미뤄") || compact.contains("뒤로") || compact.contains("나중"))) {
            Item target = resolveCurrentOrNextTask();
            if (target == null) {
                speak("미룰 일정이 없습니다.", CONTINUE_PREFIX + "snooze_none", true);
            } else {
                sendSnooze(target);
                speak(target.title + " 알림을 10분 뒤로 미뤘습니다.", CONTINUE_PREFIX + "snooze", true);
            }
            return;
        }

        askServerAi(text);
    }

    private void beginSmsRequest(SmsVoiceHelper.SmsRequest request) {
        pendingSmsRequest = request;
        awaitingSmsConfirmation = false;
        pendingSmsDisplayName = "";
        pendingSmsPhone = "";
        pendingSmsMessage = "";

        if (!activity.hasSmsPermissions()) {
            activity.setStatus("연락처 및 문자 권한을 허용해 주세요.");
            activity.requestSmsPermissions();
            return;
        }
        resolvePendingSmsRequest();
    }

    private void resolvePendingSmsRequest() {
        if (pendingSmsRequest == null) {
            speak("문자 요청을 다시 말씀해 주세요.", CONTINUE_PREFIX + "sms_missing", true);
            return;
        }

        SmsVoiceHelper.ContactResult result = SmsVoiceHelper.findBestContact(activity, pendingSmsRequest.contactQuery);
        if (result.status == SmsVoiceHelper.ContactResult.NOT_FOUND) {
            String query = pendingSmsRequest.contactQuery;
            clearPendingSms();
            speak("연락처에서 " + query + "을 찾지 못했습니다. 저장된 이름을 조금 더 포함해서 다시 말씀해 주세요.",
                    CONTINUE_PREFIX + "sms_not_found", true);
            return;
        }

        if (result.status == SmsVoiceHelper.ContactResult.AMBIGUOUS) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < result.ambiguousNames.size(); i++) {
                if (i > 0) names.append(", ");
                names.append(result.ambiguousNames.get(i));
            }
            clearPendingSms();
            String tail = names.length() > 0 ? " 후보는 " + names + "입니다." : "";
            speak("비슷한 연락처가 여러 명 있습니다." + tail + " 회사명이나 저장된 이름을 더 포함해서 다시 말씀해 주세요.",
                    CONTINUE_PREFIX + "sms_ambiguous", true);
            return;
        }

        pendingSmsDisplayName = result.displayName;
        pendingSmsPhone = result.phoneNumber;
        pendingSmsMessage = pendingSmsRequest.message;
        pendingSmsRequest = null;
        awaitingSmsConfirmation = true;

        String messageForSpeech = pendingSmsMessage;
        if (messageForSpeech.length() > 220) {
            messageForSpeech = messageForSpeech.substring(0, 220) + ". 내용이 길어서 이후 부분은 생략했습니다";
        }
        speak(pendingSmsDisplayName + "에게 다음 내용으로 문자를 보낼까요? " + messageForSpeech
                        + ". 보내 또는 취소라고 말씀해 주세요.",
                CONTINUE_PREFIX + "sms_confirm", true);
    }

    private void handleSmsConfirmation(String text) {
        String compact = text == null ? "" : text.replace(" ", "");
        boolean negative = compact.contains("취소") || compact.contains("보내지마") || compact.contains("아니") || compact.contains("됐어");
        if (negative) {
            clearPendingSms();
            speak("문자 전송을 취소했습니다.", CONTINUE_PREFIX + "sms_cancel", true);
            return;
        }

        boolean positive = compact.contains("보내") || compact.contains("전송")
                || compact.equals("응") || compact.equals("네") || compact.equals("그래") || compact.equals("좋아");
        if (!positive) {
            speak("문자를 보내려면 보내, 취소하려면 취소라고 말씀해 주세요.",
                    CONTINUE_PREFIX + "sms_confirm_again", true);
            return;
        }

        if (!activity.hasSmsPermissions()) {
            activity.setStatus("연락처 및 문자 권한을 허용해 주세요.");
            activity.requestSmsPermissions();
            return;
        }

        String name = pendingSmsDisplayName;
        String phone = pendingSmsPhone;
        String message = pendingSmsMessage;
        try {
            SmsVoiceHelper.sendSms(activity, phone, message);
            clearPendingSms();
            speak(name + "에게 문자를 보냈습니다.", CONTINUE_PREFIX + "sms_sent", true);
        } catch (SecurityException e) {
            clearPendingSms();
            speak("문자 권한이 없어 전송하지 못했습니다. 권한 설정을 확인해 주세요.",
                    CONTINUE_PREFIX + "sms_security", true);
        } catch (Exception e) {
            clearPendingSms();
            speak("문자를 보내지 못했습니다. 기본 문자 심 설정과 통신 상태를 확인해 주세요.",
                    CONTINUE_PREFIX + "sms_failed", true);
        }
    }

    private void clearPendingSms() {
        pendingSmsRequest = null;
        awaitingSmsConfirmation = false;
        pendingSmsDisplayName = "";
        pendingSmsPhone = "";
        pendingSmsMessage = "";
    }

    private Item resolveCurrentOrNextTask() {
        if (!currentTaskId.isEmpty()) {
            JSONObject r = LocalTaskStore.findTask(activity, currentTaskId);
            Item item = toItem(r);
            if (item != null) return item;
        }
        Item next = findNextTask();
        if (next != null) setCurrentTask(next);
        return next;
    }

    private void sendComplete(Item item) {
        Intent i = new Intent(activity, ReminderActionReceiver.class);
        i.setAction(ReminderActionReceiver.ACTION_COMPLETE);
        i.putExtra("id", item.id);
        i.putExtra("title", item.title);
        activity.sendBroadcast(i);
    }

    private void sendSnooze(Item item) {
        Intent i = new Intent(activity, ReminderActionReceiver.class);
        i.setAction(ReminderActionReceiver.ACTION_SNOOZE);
        i.putExtra("id", item.id);
        i.putExtra("title", item.title);
        activity.sendBroadcast(i);
    }

    private void setCurrentTask(Item item) {
        currentTaskId = item == null ? "" : item.id;
    }

    private void clearCurrentTask() {
        currentTaskId = "";
    }

    private Item findNextTask() {
        JSONArray records = LocalTaskStore.getPendingTasks(activity);
        Item best = null;
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        for (int i = 0; i < records.length(); i++) {
            Item item = toItem(records.optJSONObject(i));
            if (item == null || item.when.isBefore(now)) continue;
            if (best == null || item.when.isBefore(best.when)) best = item;
        }
        return best;
    }

    private Item toItem(JSONObject r) {
        if (r == null) return null;
        JSONObject d = r.optJSONObject("data");
        if (d == null) return null;
        ZonedDateTime due = parseDueAt(d.optString("dueAt", ""));
        if (due == null) return null;
        return new Item(r.optString("id", ""), due, d.optString("title", "할 일"));
    }

    private String formatTask(Item item, String prefix) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("M월 d일 a h시 m분", Locale.KOREAN);
        String time = item.when.format(fmt).replace(" 0분", "");
        return prefix + time + "에 " + item.title + "입니다.";
    }

    private String buildOverdueBriefing() {
        JSONArray records = LocalTaskStore.getPendingTasks(activity);
        List<Item> overdue = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        for (int i = 0; i < records.length(); i++) {
            Item item = toItem(records.optJSONObject(i));
            if (item != null && item.when.isBefore(now)) overdue.add(item);
        }
        overdue.sort(Comparator.comparing(i -> i.when));
        if (overdue.isEmpty()) return "미완료된 지난 일정은 없습니다.";

        StringBuilder sb = new StringBuilder("미완료된 지난 일정은 ").append(overdue.size()).append("건입니다. ");
        int limit = Math.min(3, overdue.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" 그리고 ");
            sb.append(overdue.get(i).title);
        }
        if (overdue.size() > limit) sb.append(" 외에 ").append(overdue.size() - limit).append("건이 더 있습니다.");
        return sb.toString();
    }

    private void askServerAi(String text) {
        activity.setStatus("AI가 확인하고 있어요…");
        new Thread(() -> {
            try {
                SharedPreferences prefs = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
                String token = prefs.getString("token", "");
                if (token == null || token.isEmpty()) {
                    speakOnUi("먼저 MyDesk AI에 연결 코드로 로그인해 주세요.", CONTINUE_PREFIX + "auth");
                    return;
                }

                URL url = new URL(MainActivity.APP_URL + "/api/assistant");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(12000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                JSONObject body = new JSONObject();
                body.put("message", text);
                body.put("threadId", "main");

                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int status = c.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream(),
                        StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                c.disconnect();

                if (status < 200 || status >= 300) {
                    speakOnUi("AI 연결에 문제가 있습니다. 잠시 후 다시 시도해 주세요.", CONTINUE_PREFIX + "server_error");
                    return;
                }

                JSONObject root = new JSONObject(sb.toString());
                String reply = root.optString("reply", "").trim();
                if (reply.isEmpty()) reply = "처리는 완료됐지만 음성으로 읽을 답변이 없습니다.";

                ReminderSyncJobService.syncNow(activity);
                speakOnUi(cleanForSpeech(reply), CONTINUE_PREFIX + "ai");
            } catch (Exception e) {
                speakOnUi("AI 응답을 가져오지 못했습니다. 잠시 후 다시 말씀해 주세요.", CONTINUE_PREFIX + "exception");
            }
        }).start();
    }

    private void speakOnUi(String text, String id) {
        activity.runOnUiThread(() -> speak(text, id, true));
    }

    private String cleanForSpeech(String raw) {
        String s = raw == null ? "" : raw;
        s = s.replaceAll("```[\\s\\S]*?```", " ");
        s = s.replaceAll("[#*_`>\\[\\]{}]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 700) s = s.substring(0, 700) + ". 자세한 내용은 화면에 남겨두었습니다.";
        return s;
    }

    private String buildBriefingForDate(LocalDate targetDate, String label) {
        JSONArray records = LocalTaskStore.getPendingTasks(activity);
        List<Item> sameDay = new ArrayList<>();
        int overdue = 0;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (int i = 0; i < records.length(); i++) {
            Item item = toItem(records.optJSONObject(i));
            if (item == null) continue;
            LocalDate date = item.when.toLocalDate();
            if (date.isBefore(today)) overdue++;
            if (date.equals(targetDate)) sameDay.add(item);
        }
        sameDay.sort(Comparator.comparing(i -> i.when));

        if (sameDay.isEmpty()) {
            if ("오늘".equals(label) && overdue > 0) {
                return "오늘 예정된 일정은 없습니다. 다만 미완료된 지난 일정이 " + overdue + "건 있습니다.";
            }
            return label + " 예정된 일정은 없습니다.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" 일정은 ").append(sameDay.size()).append("건입니다. ");
        int limit = Math.min(5, sameDay.size());
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("a h시 m분", Locale.KOREAN);
        for (int i = 0; i < limit; i++) {
            Item item = sameDay.get(i);
            if (i > 0) sb.append(" 그리고 ");
            String time = item.when.format(timeFmt).replace(" 0분", "");
            sb.append(time).append("에 ").append(item.title);
        }
        sb.append("이 있습니다.");
        if (sameDay.size() > limit) sb.append(" 그 외에 ").append(sameDay.size() - limit).append("건이 더 있습니다.");
        if ("오늘".equals(label) && overdue > 0) sb.append(" 미완료된 지난 일정은 ").append(overdue).append("건입니다.");
        setCurrentTask(sameDay.get(0));
        return sb.toString();
    }

    private ZonedDateTime parseDueAt(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim();
        ZoneId zone = ZoneId.of("Asia/Seoul");
        try { return ZonedDateTime.ofInstant(Instant.parse(s), zone); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(s).atZone(zone); } catch (Exception ignored) {}
        return null;
    }

    private void speak(String text, String id, boolean remember) {
        if (tts == null || !ttsReady || text == null || text.isEmpty() || destroyed) return;
        if (remember) lastSpoken = text;
        activity.setStatus(text);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    public void destroy() {
        destroyed = true;
        clearPendingSms();
        destroyRecognizer();
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
            try { tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
    }

    private static class Item {
        final String id;
        final ZonedDateTime when;
        final String title;

        Item(String id, ZonedDateTime when, String title) {
            this.id = id;
            this.when = when;
            this.title = title;
        }
    }
}
