package com.mydesk.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class VoiceAssistantActivity extends Activity {
    public static final int REQ_RECORD_AUDIO = 2201;
    public static final int REQ_SMS_PERMISSIONS = 2202;
    private VoiceAssistantManager manager;
    private TextView statusView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        manager = new VoiceAssistantManager(this);
        manager.start();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(56, 48, 56, 48);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("MyDesk AI 음성호출");
        title.setTextSize(22f);
        title.setTextColor(Color.rgb(45, 45, 60));
        title.setGravity(Gravity.CENTER);

        statusView = new TextView(this);
        statusView.setText("음성 비서를 준비하고 있어요…");
        statusView.setTextSize(18f);
        statusView.setTextColor(Color.rgb(75, 75, 90));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 28, 0, 24);

        Button notificationSettings = new Button(this);
        notificationSettings.setText("알림 소리 · 진동 설정");
        notificationSettings.setOnClickListener(v -> openNotificationSettings());

        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(notificationSettings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void openNotificationSettings() {
        ReminderReceiver.ensureSoundChannel(this);
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderReceiver.SOUND_CHANNEL_ID);
            } else {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            }
            startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    public boolean hasSmsPermissions() {
        return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    public void requestSmsPermissions() {
        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS}, REQ_SMS_PERMISSIONS);
    }

    public void setStatus(String text) {
        runOnUiThread(() -> {
            if (statusView != null) statusView.setText(text == null ? "" : text);
        });
    }

    public void finishAfterDelay(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, delayMs);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (manager == null) return;

        if (requestCode == REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            manager.onMicrophonePermissionResult(granted);
        } else if (requestCode == REQ_SMS_PERMISSIONS) {
            manager.onSmsPermissionsResult(hasSmsPermissions());
        }
    }

    @Override protected void onDestroy() {
        if (manager != null) manager.destroy();
        manager = null;
        super.onDestroy();
    }
}
