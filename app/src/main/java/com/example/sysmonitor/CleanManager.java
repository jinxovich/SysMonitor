package com.example.sysmonitor;

import android.content.Context;

public class CleanManager {
    private static final String PREFS = "sys_prefs";
    private static final String KEY_LAST_CLEAN = "last_clean_time";

    public static void saveCleanTime(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CLEAN, System.currentTimeMillis()).apply();
    }

    public static String getFormattedLastCleanTime(Context context) {
        long lastTime = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CLEAN, 0);
        if (lastTime == 0) return "Never cleaned";

        long diffMinutes = (System.currentTimeMillis() - lastTime) / 1000 / 60;
        if (diffMinutes < 60) return diffMinutes + " min ago";

        long diffHours = diffMinutes / 60;
        return diffHours + " hours ago";
    }

    public static int getSystemHealthScore(Context context) {
        long lastTime = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CLEAN, 0);
        if (lastTime == 0) return 50;

        long diffHours = (System.currentTimeMillis() - lastTime) / (1000 * 60 * 60);

        int health = 100 - (int)(diffHours * 2);
        return Math.max(30, Math.min(100, health));
    }
}