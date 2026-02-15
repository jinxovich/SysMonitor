package com.example.sysmonitor;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class SystemStatsRepository {
    private static final String TAG = "SystemStatsRepo";
    private final Context context;

    public SystemStatsRepository(Context context) {
        this.context = context;
    }

    public long getCpuFrequency() {
        String[] paths = {
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
                "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq"
        };

        for (String path : paths) {
            File file = new File(path);
            if (file.exists() && file.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line = br.readLine();
                    if (line != null) {
                        return Long.parseLong(line.trim());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error reading CPU freq from " + path, e);
                }
            }
        }
        return 0;
    }

    public float getDisplayRefreshRate() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display display = wm.getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return display.getMode().getRefreshRate();
            }
            return display.getRefreshRate();
        }
        return 60.0f;
    }

    public boolean checkRootAccess() {
        String[] paths = {
                "/sbin/su", "/system/bin/su", "/system/xbin/su",
                "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
                "/system/bin/failsafe/su", "/data/local/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        return false;
    }

    public BatteryInfo getBatteryInfo() {
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) return new BatteryInfo(0, 0);

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

        int pct = (level > 0 && scale > 0) ? (int) ((level / (float) scale) * 100) : 0;
        return new BatteryInfo(pct, temp / 10.0f);
    }

    // Класс-контейнер для батареи
    public static class BatteryInfo {
        public int level;
        public float temp;
        public BatteryInfo(int l, float t) { level = l; temp = t; }
    }
}