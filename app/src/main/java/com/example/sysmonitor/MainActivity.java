package com.example.sysmonitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.net.LinkAddress;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView textModel, textCpu, textCpuFreq;
    private TextView textRam, btnCleanRam;
    private TextView textBattery, textBatTemp;
    private TextView textIp;
    private TextView textUptime, textStorage, textRoot;

    private ProgressBar progressRam, progressBattery, progressStorage;
    private RecyclerView recyclerApps;
    private CardView cardDevice, cardSensors; // Кнопка сенсоров

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateRealtimeMetrics();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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

    private void initializeViews() {
        // Device Info
        cardDevice = findViewById(R.id.cardDevice);
        cardSensors = findViewById(R.id.cardSensors);
        textModel = findViewById(R.id.textModel);
        textCpu = findViewById(R.id.textCpu);
        textCpuFreq = findViewById(R.id.textCpuFreq);

        // Resources
        textRam = findViewById(R.id.textRam);
        btnCleanRam = findViewById(R.id.btnCleanRam);
        progressRam = findViewById(R.id.progressRam);
        textBattery = findViewById(R.id.textBattery);
        textBatTemp = findViewById(R.id.textBatTemp);
        progressBattery = findViewById(R.id.progressBattery);

        // Network
        textIp = findViewById(R.id.textIp);

        // Disk & OS
        textStorage = findViewById(R.id.textStorage);
        progressStorage = findViewById(R.id.progressStorage);
        textRoot = findViewById(R.id.textRoot);

        // System Status
        textUptime = findViewById(R.id.textUptime);
        recyclerApps = findViewById(R.id.recyclerApps);
    }

    private void setupInteractions() {
        cardDevice.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        cardSensors.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SensorActivity.class));
        });

        btnCleanRam.setOnClickListener(v -> {
            cleanRamMemory();
        });
    }

    private void cleanRamMemory() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);

        long memoryBefore = getAvailableMemory();

        for (ApplicationInfo app : apps) {
            if (!app.packageName.equals(getPackageName())) {
                am.killBackgroundProcesses(app.packageName);
            }
        }

        long memoryAfter = getAvailableMemory();
        long diff = memoryAfter - memoryBefore;

        String msg = (diff > 0)
                ? "Freed: " + (diff / 1024 / 1024) + " MB"
                : "Optimized (System limits reached)";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

        updateRealtimeMetrics();
    }

    private long getAvailableMemory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.availMem;
    }

    private void setupStaticData() {
        textModel.setText(String.format("%s %s (Android %s)",
                Build.MANUFACTURER.toUpperCase(), Build.MODEL, Build.VERSION.RELEASE));

        new Thread(() -> {
            String cpuName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                cpuName = Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL;
            } else {
                cpuName = getCpuNameLegacy();
            }

            if (cpuName.contains("null") || cpuName.trim().isEmpty()) {
                cpuName = Build.HARDWARE;
            }

            String finalCpu = cpuName;
            runOnUiThread(() -> textCpu.setText("CPU: " + finalCpu));
        }).start();

        updateStorageInfo();
        checkRootAccess();
    }

    private void updateRealtimeMetrics() {
        // --- RAM ---
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        double totalGB = mi.totalMem / 1073741824.0;
        double usedGB = (mi.totalMem - mi.availMem) / 1073741824.0;
        int usedPercent = (int) ((usedGB / totalGB) * 100);

        textRam.setText(String.format(Locale.US, "RAM: %.2f / %.2f GB (%d%%)", usedGB, totalGB, usedPercent));
        progressRam.setProgress(usedPercent);

        // --- BATTERY & TEMP ---
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

            int batLevel = (int) ((level / (float) scale) * 100);
            textBattery.setText("Battery: " + batLevel + "%");
            progressBattery.setProgress(batLevel);

            float tempC = temp / 10.0f;
            textBatTemp.setText(String.format(Locale.US, "%.1f°C", tempC));

            if (tempC < 38.0) textBatTemp.setTextColor(0xFF4CAF50); // Зеленый
            else if (tempC < 42.0) textBatTemp.setTextColor(0xFFFFB74D); // Оранжевый
            else textBatTemp.setTextColor(0xFFFF5252); // Красный
        }

        // --- UPTIME ---
        long s = android.os.SystemClock.elapsedRealtime() / 1000;
        textUptime.setText(String.format(Locale.US, "Uptime: %02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60));

        // --- NETWORK ---
        updateNetworkInfo();

        // --- CPU FREQ & DISPLAY ---
        long currentFreq = getCurrentCpuFreq();
        float displayRate = getDisplayRefreshRate();
        textCpuFreq.setText(String.format(Locale.US, "Core Speed: %d MHz  |  Disp: %.0f Hz",
                currentFreq / 1000, displayRate));
    }

    private void updateNetworkInfo() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network activeNetwork = cm.getActiveNetwork();

        String netType = "Disconnected";
        String ipAddr = "Unavailable";

        if (activeNetwork != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            LinkProperties props = cm.getLinkProperties(activeNetwork);

            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) netType = "Wi-Fi";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) netType = "Mobile Data";
            }

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
    }

    private void checkRootAccess() {
        String[] paths = { "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su" };
        boolean isRooted = false;
        for (String p : paths) {
            if (new File(p).exists()) { isRooted = true; break; }
        }
        if (isRooted) {
            textRoot.setText("Root Access: DETECTED");
            textRoot.setTextColor(0xFFFF5252);
        } else {
            textRoot.setText("Root Access: Safe (Not Detected)");
            textRoot.setTextColor(0xFF4CAF50);
        }
    }

    private long getCurrentCpuFreq() {
        try (BufferedReader br = new BufferedReader(new FileReader("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))) {
            return Long.parseLong(br.readLine().trim());
        } catch (Exception e) { return 0; }
    }

    private float getDisplayRefreshRate() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        return display.getRefreshRate();
    }

    // Старый метод чтения из файла (для старых Android)
    private String getCpuNameLegacy() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Hardware")) return line.split(":")[1].trim();
            }
        } catch (Exception e) { }
        return "Not identified";
    }

    // --- РАБОТА СО СПИСКОМ ПРИЛОЖЕНИЙ ---
    private void setupAppList() {
        recyclerApps.setLayoutManager(new LinearLayoutManager(this));

        new Thread(() -> {
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
        }).start();
    }

    private void launchApp(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "Cannot launch this app", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error launching app", Toast.LENGTH_SHORT).show();
        }
    }

    private static class AppItem {
        String name; String packageName; Drawable icon;
        public AppItem(String n, String p, Drawable i) { name=n; packageName=p; icon=i; }
    }

    private static class AppAdapter extends RecyclerView.Adapter<AppAdapter.AppViewHolder> {
        private final List<AppItem> list;
        private final OnAppClickListener listener;

        public interface OnAppClickListener {
            void onAppClick(String packageName);
        }

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
