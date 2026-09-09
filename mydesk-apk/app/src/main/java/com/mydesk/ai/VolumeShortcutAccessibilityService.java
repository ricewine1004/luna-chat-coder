package com.mydesk.ai;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class VolumeShortcutAccessibilityService extends AccessibilityService {
    private static final long HOLD_MS = 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean volumeUpDown = false;
    private boolean triggered = false;

    private final Runnable holdRunnable = () -> {
        if (!volumeUpDown || triggered || !isScreenInteractive()) return;
        triggered = true;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(MainActivity.EXTRA_VOICE_ASSISTANT, true);
        startActivity(i);
    };

    @Override public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP) return false;
        if (!isScreenInteractive()) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!volumeUpDown) {
                volumeUpDown = true;
                triggered = false;
                handler.postDelayed(holdRunnable, HOLD_MS);
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            volumeUpDown = false;
            handler.removeCallbacks(holdRunnable);
            boolean consume = triggered;
            triggered = false;
            return consume;
        }
        return false;
    }

    private boolean isScreenInteractive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
