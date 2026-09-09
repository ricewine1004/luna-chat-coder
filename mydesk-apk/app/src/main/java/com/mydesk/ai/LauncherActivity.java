package com.mydesk.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

public class LauncherActivity extends Activity {
    private static final String PREF_GUIDE_SHOWN = "voice_shortcut_guide_shown";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isShortcutServiceEnabled() && !getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(PREF_GUIDE_SHOWN, false)) {
            showGuide();
        } else {
            openMain();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (isShortcutServiceEnabled() && !isFinishing()) openMain();
    }

    private void showGuide() {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean(PREF_GUIDE_SHOWN, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("MyDesk AI 음성 호출")
                .setMessage("화면이 켜진 상태에서 볼륨 + 버튼을 약 1초 길게 누르면 MyDesk AI 음성 비서가 실행됩니다.\n\n처음 한 번만 접근성 설정에서 MyDesk AI를 허용해 주세요.")
                .setPositiveButton("설정 열기", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (Exception ignored) { openMain(); }
                })
                .setNegativeButton("나중에", (d, w) -> openMain())
                .setOnCancelListener(d -> openMain())
                .show();
    }

    private boolean isShortcutServiceEnabled() {
        ComponentName expected = new ComponentName(this, VolumeShortcutAccessibilityService.class);
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName c = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(c)) return true;
        }
        return false;
    }

    private void openMain() {
        if (isFinishing()) return;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
