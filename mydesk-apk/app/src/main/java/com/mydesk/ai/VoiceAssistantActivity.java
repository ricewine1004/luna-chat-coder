package com.mydesk.ai;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class VoiceAssistantActivity extends Activity {
    public static final int REQ_RECORD_AUDIO = 2201;
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
        title.setText("MyDesk AI");
        title.setTextSize(22f);
        title.setTextColor(Color.rgb(45, 45, 60));
        title.setGravity(Gravity.CENTER);

        statusView = new TextView(this);
        statusView.setText("음성 비서를 준비하고 있어요…");
        statusView.setTextSize(18f);
        statusView.setTextColor(Color.rgb(75, 75, 90));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 28, 0, 0);

        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
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
        if (requestCode == REQ_RECORD_AUDIO && manager != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            manager.onMicrophonePermissionResult(granted);
        }
    }

    @Override protected void onDestroy() {
        if (manager != null) manager.destroy();
        manager = null;
        super.onDestroy();
    }
}
