package com.example.sysmonitor;

import android.app.ActivityManager;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView textModel, textCpu, textCpuFreq, btnDetails;
    private TextView textRam, btnCleanRam;
    private TextView textBattery, textBatTemp;
    private TextView textIp;
    private TextView textUptime, textStorage, textRoot;

    private ProgressBar progressRam, progressBattery, progressStorage;
    private RecyclerView recyclerApps;
    private CardView cardDevice, cardSensors;

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

        statsRepo = new SystemStatsRepository(this);

        initializeViews();
        setupStaticData();
        setupInteractions();
        setupAppList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusUpdater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(statusUpdater);
        executor.shutdown();
    }

    private void initializeViews() {
        cardDevice = findViewById(R.id.cardDevice);
        cardSensors = findViewById(R.id.cardSensors);
        textModel = findViewById(R.id.textModel);
        textCpu = findViewById(R.id.textCpu);
        textCpuFreq = findViewById(R.id.textCpuFreq);
        btnDetails = findViewById(R.id.btnDetails); // Новая кнопка

        textRam = findViewById(R.id.textRam);
        btnCleanRam = findViewById(R.id.btnCleanRam);
        progressRam = findViewById(R.id.progressRam);
        textBattery = findViewById(R.id.textBattery);
        textBatTemp = findViewById(R.id.textBatTemp);
        progressBattery = findViewById(R.id.progressBattery);
        textIp = findViewById(R.id.textIp);
        textStorage = findViewById(R.id.textStorage);
        progressStorage = findViewById(R.id.progressStorage);
        textRoot = findViewById(R.id.textRoot);
        textUptime = findViewById(R.id.textUptime);
        recyclerApps = findViewById(R.id.recyclerApps);

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

    private void updateRealtimeMetrics() {
        executor.execute(() -> {
            long freq = statsRepo.getCpuFrequency();
            float refreshRate = statsRepo.getDisplayRefreshRate();
            SystemStatsRepository.BatteryInfo batInfo = statsRepo.getBatteryInfo();

            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) am.getMemoryInfo(mi);

            runOnUiThread(() -> {
                // CPU & Screen
                textCpuFreq.setText(String.format(Locale.US, "Core Speed: %d MHz  |  Disp: %.0f Hz",
                        freq / 1000, refreshRate));

                // Battery
                textBattery.setText("Battery: " + batInfo.level + "%");
                progressBattery.setProgress(batInfo.level);
                textBatTemp.setText(String.format(Locale.US, "%.1f°C", batInfo.temp));

                if (batInfo.temp < 38.0) textBatTemp.setTextColor(0xFF4CAF50);
                else if (batInfo.temp < 42.0) textBatTemp.setTextColor(0xFFFFB74D);
                else textBatTemp.setTextColor(0xFFFF5252);

                // RAM
                double totalGB = mi.totalMem / 1073741824.0;
                double usedGB = (mi.totalMem - mi.availMem) / 1073741824.0;
                int usedPercent = (int) ((usedGB / totalGB) * 100);
                textRam.setText(String.format(Locale.US, "RAM: %.2f / %.2f GB (%d%%)", usedGB, totalGB, usedPercent));
                progressRam.setProgress(usedPercent);

                // Uptime
                long s = android.os.SystemClock.elapsedRealtime() / 1000;
                textUptime.setText(String.format(Locale.US, "Uptime: %02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60));
            });
        });
        updateNetworkInfo();
    }

    private void updateNetworkInfo() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;

        Network activeNetwork = cm.getActiveNetwork();
        String netType = "Disconnected";
        String ipAddr = "Unavailable";

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
                    if (s != null && s.indexOf(':') < 0) {
                        ipAddr = s;
                        break;
                    }
                }
            }
        }
        textIp.setText(String.format("Type: %s  |  IP: %s", netType, ipAddr));
    }

    private void updateStorageInfo() {
        try {
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            long totalSpace = stat.getBlockCountLong() * stat.getBlockSizeLong();
            long freeSpace = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            long usedSpace = totalSpace - freeSpace;

            double totalGB = totalSpace / 1073741824.0;
            double usedGB = usedSpace / 1073741824.0;
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

        // Переход на новую активность HardwareActivity
        btnDetails.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, HardwareActivity.class));
        });

        cardSensors.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SensorActivity.class)));

        btnCleanRam.setOnClickListener(v -> cleanRamMemory());
    }

    private void cleanRamMemory() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);

        long memoryBefore = getAvailableMemory();
        int count = 0;

        for (ApplicationInfo app : apps) {
            if (!app.packageName.equals(getPackageName())) {
                try {
                    am.killBackgroundProcesses(app.packageName);
                    count++;
                } catch (Exception e) { }
            }
        }

        long memoryAfter = getAvailableMemory();
        long diff = memoryAfter - memoryBefore;

        String msg = (diff > 0)
                ? "Freed: " + (diff / 1024 / 1024) + " MB"
                : "Optimized " + count + " processes";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppItem> userApps = new ArrayList<>();

            for (ApplicationInfo app : packages) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    try {
                        String name = app.loadLabel(pm).toString();
                        Drawable icon = app.loadIcon(pm);
                        userApps.add(new AppItem(name, app.packageName, icon));
                    } catch (Exception e) {}
                }
            }
            Collections.sort(userApps, (o1, o2) -> o1.name.compareToIgnoreCase(o2.name));

            runOnUiThread(() -> recyclerApps.setAdapter(new AppAdapter(userApps, this::launchApp)));
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
        String name; String packageName; Drawable icon;
        public AppItem(String n, String p, Drawable i) { name=n; packageName=p; icon=i; }
    }

    private static class AppAdapter extends RecyclerView.Adapter<AppAdapter.AppViewHolder> {
        private final List<AppItem> list;
        private final OnAppClickListener listener;

        public interface OnAppClickListener { void onAppClick(String packageName); }

        public AppAdapter(List<AppItem> list, OnAppClickListener listener) {
            this.list = list; this.listener = listener;
        }

        @NonNull @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new AppViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_app, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull AppViewHolder h, int pos) {
            AppItem i = list.get(pos);
            h.name.setText(i.name);
            h.pkg.setText(i.packageName);
            h.icon.setImageDrawable(i.icon);
            h.itemView.setOnClickListener(v -> listener.onAppClick(i.packageName));
        }

        @Override public int getItemCount() { return list.size(); }

        static class AppViewHolder extends RecyclerView.ViewHolder {
            TextView name, pkg; ImageView icon;
            public AppViewHolder(@NonNull View v) {
                super(v);
                name=v.findViewById(R.id.appName);
                pkg=v.findViewById(R.id.appPackage);
                icon=v.findViewById(R.id.appIcon);
            }
        }
    }
}