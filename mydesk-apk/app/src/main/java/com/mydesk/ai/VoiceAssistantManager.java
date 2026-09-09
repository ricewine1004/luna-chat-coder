package com.mydesk.ai;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private final VoiceAssistantActivity activity;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private boolean ttsReady = false;
    private boolean startWhenReady = false;

    public VoiceAssistantManager(VoiceAssistantActivity activity) {
        this.activity = activity;
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(activity.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(Locale.KOREA);
            tts.setSpeechRate(0.88f);
            tts.setPitch(1.0f);
            chooseBestKoreanVoice();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onError(String utteranceId) { activity.runOnUiThread(activity::finish); }
                @Override public void onDone(String utteranceId) {
                    if (ACK_ID.equals(utteranceId)) {
                        activity.runOnUiThread(VoiceAssistantManager.this::startListening);
                    } else {
                        activity.runOnUiThread(() -> activity.finishAfterDelay(450));
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
        speak("네. 말씀하세요.", ACK_ID);
    }

    public void onMicrophonePermissionResult(boolean granted) {
        if (granted) start(); else speak("마이크 권한이 필요합니다.", "mic_denied");
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            speak("음성 인식 기능을 사용할 수 없습니다.", "recognizer_missing");
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
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    speak("잘 듣지 못했어요. 다시 호출해 주세요.", "listen_retry");
                } else {
                    speak("음성 인식을 다시 시도해 주세요.", "listen_error");
                }
                destroyRecognizer();
            }

            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = list == null || list.isEmpty() ? "" : list.get(0).trim();
                activity.setStatus(text.isEmpty() ? "잘 듣지 못했어요" : "“" + text + "”");
                handleCommand(text);
                destroyRecognizer();
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
            speak("잘 듣지 못했어요. 다시 호출해 주세요.", "empty");
            return;
        }
        String compact = text.replace(" ", "");
        if ((compact.contains("오늘") && (compact.contains("알림") || compact.contains("일정") || compact.contains("할일") || compact.contains("브리핑")))
                || compact.contains("오늘뭐있어") || compact.contains("오늘뭐해야")) {
            speak(buildBriefingForDate(LocalDate.now(ZoneId.of("Asia/Seoul")), "오늘"), "today_briefing");
            return;
        }
        if (compact.contains("내일") && (compact.contains("알림") || compact.contains("일정") || compact.contains("할일"))) {
            speak(buildBriefingForDate(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1), "내일"), "tomorrow_briefing");
            return;
        }
        speak("현재 음성 모드에서는 오늘이나 내일 일정 안내를 지원합니다.", "unsupported");
    }

    private String buildBriefingForDate(LocalDate targetDate, String label) {
        JSONArray records = LocalTaskStore.getPendingTasks(activity);
        List<Item> sameDay = new ArrayList<>();
        int overdue = 0;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            JSONObject d = r.optJSONObject("data");
            if (d == null) continue;
            ZonedDateTime due = parseDueAt(d.optString("dueAt", ""));
            if (due == null) continue;
            LocalDate date = due.toLocalDate();
            if (date.isBefore(today)) overdue++;
            if (date.equals(targetDate)) sameDay.add(new Item(due, d.optString("title", "할 일")));
        }
        sameDay.sort(Comparator.comparing(i -> i.when));

        if (sameDay.isEmpty()) {
            if ("오늘".equals(label) && overdue > 0) return "오늘 예정된 일정은 없습니다. 다만 미완료된 지난 일정이 " + overdue + "건 있습니다.";
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

    private void speak(String text, String id) {
        if (tts == null || !ttsReady || text == null || text.isEmpty()) return;
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
        destroyRecognizer();
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
            try { tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
    }

    private static class Item {
        final ZonedDateTime when;
        final String title;
        Item(ZonedDateTime when, String title) { this.when = when; this.title = title; }
    }
}
