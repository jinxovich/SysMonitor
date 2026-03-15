package com.example.sysmonitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView textModel, textCpu, textCpuFreq, btnDetails;
    private TextView textRam, btnCleanRam;
    private TextView textBattery, textBatTemp;
    private TextView textIp;
    private TextView textUptime, textStorage, textRoot;

    private TextView textHealthScore, textLastClean, textHealthLevel, textHealthDesc;
    private ProgressBar progressHealth;

    private ProgressBar progressRam, progressBattery, progressStorage;
    private RecyclerView recyclerApps;
    private CardView cardDevice, cardSensors, cardHygiene;

    private SystemStatsRepository statsRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String cachedCpuName = "Loading...";
    private boolean isRooted = false;

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            updateRealtimeMetrics();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ── Отступ от статус-бара / вырез камеры ────────────────────────────
        View rootView = findViewById(R.id.rootScrollView);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                v.setPadding(
                        v.getPaddingLeft(),
                        topInset + 16,
                        v.getPaddingRight(),
                        v.getPaddingBottom()
                );
                return insets;
            });
        }

        statsRepo = new SystemStatsRepository(this);
        checkUsagePermission();

        initializeViews();
        setupStaticData();
        setupInteractions();
        setupAppList();
        updateHealthUI();
    }

    private void checkUsagePermission() {
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long time = System.currentTimeMillis();
                java.util.List<android.app.usage.UsageStats> stats =
                        usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                                time - 1000 * 60, time);
                if (stats == null || stats.isEmpty()) {
                    Toast.makeText(this, "Please grant Usage Access for App Stats", Toast.LENGTH_LONG).show();
                    try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                    catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Usage Access check failed", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(statusUpdater);
        updateHealthUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusUpdater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void initializeViews() {
        textHealthScore = findViewById(R.id.textHealthScore);
        textLastClean   = findViewById(R.id.textLastClean);
        textHealthLevel = findViewById(R.id.textHealthLevel);
        textHealthDesc  = findViewById(R.id.textHealthDesc);
        progressHealth  = findViewById(R.id.progressHealth);

        cardDevice  = findViewById(R.id.cardDevice);
        cardSensors = findViewById(R.id.cardSensors);
        cardHygiene = findViewById(R.id.cardHygiene);

        textModel   = findViewById(R.id.textModel);
        textCpu     = findViewById(R.id.textCpu);
        textCpuFreq = findViewById(R.id.textCpuFreq);
        btnDetails  = findViewById(R.id.btnDetails);

        textRam         = findViewById(R.id.textRam);
        btnCleanRam     = findViewById(R.id.btnCleanRam);
        progressRam     = findViewById(R.id.progressRam);
        textBattery     = findViewById(R.id.textBattery);
        textBatTemp     = findViewById(R.id.textBatTemp);
        progressBattery = findViewById(R.id.progressBattery);
        textIp          = findViewById(R.id.textIp);
        textStorage     = findViewById(R.id.textStorage);
        progressStorage = findViewById(R.id.progressStorage);
        textRoot        = findViewById(R.id.textRoot);
        textUptime      = findViewById(R.id.textUptime);
        recyclerApps    = findViewById(R.id.recyclerApps);

        recyclerApps.setNestedScrollingEnabled(false);
    }

    private void setupStaticData() {
        textModel.setText(String.format("%s %s (Android %s)",
                Build.MANUFACTURER.toUpperCase(), Build.MODEL, Build.VERSION.RELEASE));

        executor.execute(() -> {
            String cpuName = Build.HARDWARE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                cpuName = Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL;
            }
            isRooted = statsRepo.checkRootAccess();
            cachedCpuName = cpuName;

            runOnUiThread(() -> {
                textCpu.setText("CPU: " + cachedCpuName);
                if (isRooted) {
                    textRoot.setText("Root Access: DETECTED");
                    textRoot.setTextColor(0xFFFF5252);
                } else {
                    textRoot.setText("Root Access: Safe");
                    textRoot.setTextColor(0xFF4CAF50);
                }
                updateStorageInfo();
            });
        });
    }

    // ── Геймифицированный Health UI ──────────────────────────────────────────
    private void updateHealthUI() {
        int health = CleanManager.getSystemHealthScore(this);

        textHealthScore.setText(health + "%");

        String level, desc;
        int color;

        if (health >= 90) {
            level = "🏆 PLATINUM"; desc = "System is in perfect condition";
            color = 0xFF80D8FF;
        } else if (health >= 75) {
            level = "🥇 GOLD"; desc = "Running great, keep it up!";
            color = 0xFFFFD54F;
        } else if (health >= 55) {
            level = "🥈 SILVER"; desc = "Some cleanup recommended";
            color = 0xFFB0BEC5;
        } else if (health >= 35) {
            level = "🥉 BRONZE"; desc = "Needs attention — scan now";
            color = 0xFFFF8A65;
        } else {
            level = "💀 CRITICAL"; desc = "Run Hygiene scan immediately!";
            color = 0xFFFF5252;
        }

        textHealthScore.setTextColor(color);

        if (textHealthLevel != null) {
            textHealthLevel.setText(level);
            textHealthLevel.setTextColor(color);
        }
        if (textHealthDesc != null) {
            textHealthDesc.setText(desc);
        }
        if (progressHealth != null) {
            progressHealth.setProgress(health);
            progressHealth.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        }

        textLastClean.setText("Last clean: " + CleanManager.getFormattedLastCleanTime(this));
    }

    private void updateRealtimeMetrics() {
        executor.execute(() -> {
            long freq = statsRepo.getCpuFrequency();
            float refreshRate = statsRepo.getDisplayRefreshRate();
            SystemStatsRepository.BatteryInfo battery = statsRepo.getBatteryInfo();

            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);

            long totalRam = mi.totalMem;
            long usedRam  = totalRam - mi.availMem;
            int  ramPct   = (totalRam > 0) ? (int) ((usedRam * 100L) / totalRam) : 0;

            runOnUiThread(() -> {
                String freqStr = freq > 0 ? (freq / 1000) + " MHz  |  " + refreshRate + " Hz" : "N/A";
                textCpuFreq.setText("Freq: " + freqStr);
                textRam.setText(String.format("RAM: %s / %s",
                        Formatter.formatFileSize(this, usedRam),
                        Formatter.formatFileSize(this, totalRam)));
                progressRam.setProgress(ramPct);
                textBattery.setText("Battery: " + battery.level + "%");
                textBatTemp.setText(String.format(Locale.US, "Temp: %.1f°C", battery.temp));
                progressBattery.setProgress(battery.level);

                long s = android.os.SystemClock.elapsedRealtime() / 1000;
                textUptime.setText(String.format(Locale.US, "Uptime: %02d:%02d:%02d",
                        s / 3600, (s % 3600) / 60, s % 60));
            });
        });
        updateNetworkInfo();
    }

    private void updateNetworkInfo() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;

            Network activeNetwork = cm.getActiveNetwork();
            String netType = "Disconnected";
            String ipAddr  = "Unavailable";

            if (activeNetwork != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) netType = "Wi-Fi";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) netType = "Mobile Data";
                }
                LinkProperties props = cm.getLinkProperties(activeNetwork);
                if (props != null) {
                    for (LinkAddress linkAddr : props.getLinkAddresses()) {
                        InetAddress addr = linkAddr.getAddress();
                        String s = addr.getHostAddress();
                        if (s != null && s.indexOf(':') < 0) { ipAddr = s; break; }
                    }
                }
            }
            textIp.setText(String.format("Type: %s  |  IP: %s", netType, ipAddr));
        } catch (Exception ignored) {}
    }

    private void updateStorageInfo() {
        try {
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            long totalSpace = stat.getBlockCountLong() * stat.getBlockSizeLong();
            long freeSpace  = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            long usedSpace  = totalSpace - freeSpace;

            double totalGB = totalSpace / 1073741824.0;
            double usedGB  = usedSpace  / 1073741824.0;
            int p = (int) ((usedSpace * 100) / totalSpace);

            textStorage.setText(String.format(Locale.US, "Storage: %.1f / %.1f GB", usedGB, totalGB));
            progressStorage.setProgress(p);
        } catch (Exception e) {
            textStorage.setText("Storage: Error");
        }
    }

    private void setupInteractions() {
        cardDevice.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });

        btnDetails.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, HardwareActivity.class)));
        cardSensors.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SensorActivity.class)));
        cardHygiene.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, HygieneActivity.class)));
        btnCleanRam.setOnClickListener(v -> cleanRamMemory());
    }

    private void cleanRamMemory() {
        Toast.makeText(this, "Cleaning...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);

            long memoryBefore = getAvailableMemory();
            int count = 0;

            for (ApplicationInfo app : apps) {
                if (!app.packageName.equals(getPackageName())) {
                    try { am.killBackgroundProcesses(app.packageName); count++; }
                    catch (Exception ignored) {}
                }
            }

            long memoryAfter = getAvailableMemory();
            long diff = memoryAfter - memoryBefore;
            int finalCount = count;

            runOnUiThread(() -> {
                CleanManager.saveCleanTime(this);
                updateHealthUI();
                String msg = (diff > 0)
                        ? "Freed: " + (diff / 1024 / 1024) + " MB"
                        : "Optimized " + finalCount + " processes";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private long getAvailableMemory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) am.getMemoryInfo(mi);
        return mi.availMem;
    }

    private void setupAppList() {
        recyclerApps.setLayoutManager(new LinearLayoutManager(this));

        executor.execute(() -> {
            long endTime   = System.currentTimeMillis();
            long startTime = endTime - (24 * 60 * 60 * 1000);

            Map<String, Long> usageTimes    = statsRepo.getForegroundUsage(startTime, endTime);
            Map<Integer, Long> networkBytes = statsRepo.getNetworkUsageStats(startTime, endTime);

            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppItem> userApps = new ArrayList<>();

            for (ApplicationInfo app : packages) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        || (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    try {
                        String   name    = app.loadLabel(pm).toString();
                        Drawable icon    = app.loadIcon(pm);
                        long     ut      = usageTimes.containsKey(app.packageName)
                                ? usageTimes.get(app.packageName) : 0;
                        long     nb      = networkBytes.containsKey(app.uid)
                                ? networkBytes.get(app.uid) : 0;
                        userApps.add(new AppItem(name, app.packageName, icon, ut, nb));
                    } catch (Exception ignored) {}
                }
            }
            Collections.sort(userApps, (o1, o2) -> Long.compare(o2.networkBytes, o1.networkBytes));

            runOnUiThread(() ->
                    recyclerApps.setAdapter(new AppAdapter(userApps, this::launchApp, this)));
        });
    }

    private void launchApp(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot launch app", Toast.LENGTH_SHORT).show();
        }
    }

    private static class AppItem {
        String name, packageName;
        Drawable icon;
        long usageTimeMs, networkBytes;
        AppItem(String n, String p, Drawable i, long ut, long nb) {
            name = n; packageName = p; icon = i; usageTimeMs = ut; networkBytes = nb;
        }
    }

    private static class AppAdapter extends RecyclerView.Adapter<AppAdapter.AppViewHolder> {
        private final List<AppItem> list;
        private final OnAppClickListener listener;
        private final Context context;

        interface OnAppClickListener { void onAppClick(String packageName); }

        AppAdapter(List<AppItem> list, OnAppClickListener l, Context ctx) {
            this.list = list; this.listener = l; this.context = ctx;
        }

        @NonNull @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new AppViewHolder(
                    LayoutInflater.from(p.getContext()).inflate(R.layout.item_app, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull AppViewHolder h, int pos) {
            AppItem i = list.get(pos);
            h.name.setText(i.name);
            h.pkg.setText(i.packageName);
            if (i.icon != null) h.icon.setImageDrawable(i.icon);
            h.stats.setText(String.format("Time: %s | Data: %s",
                    DateUtils.formatElapsedTime(i.usageTimeMs / 1000),
                    Formatter.formatFileSize(context, i.networkBytes)));
            h.itemView.setOnClickListener(v -> listener.onAppClick(i.packageName));
        }

        @Override public int getItemCount() { return list.size(); }

        static class AppViewHolder extends RecyclerView.ViewHolder {
            TextView name, pkg, stats; ImageView icon;
            AppViewHolder(@NonNull View v) {
                super(v);
                name  = v.findViewById(R.id.appName);
                pkg   = v.findViewById(R.id.appPackage);
                stats = v.findViewById(R.id.appStats);
                icon  = v.findViewById(R.id.appIcon);
            }
        }
    }
}