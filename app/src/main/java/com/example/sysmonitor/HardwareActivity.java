package com.example.sysmonitor;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HardwareActivity extends AppCompatActivity {

    private LinearLayout container;
    private SystemStatsRepository repo;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing()) {
                fetchData();
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hardware);

        container = findViewById(R.id.containerHardware);
        repo = new SystemStatsRepository(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(updater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(updater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void fetchData() {
        executor.execute(() -> {
            int[] cores = repo.getCpuCoresFreq();
            String thermal = repo.getThermalStat();

            runOnUiThread(() -> updateUI(cores, thermal));
        });
    }

    private void updateUI(int[] cores, String thermal) {
        container.removeAllViews();

        addHeader("CPU CORES FREQUENCY");
        for (int i = 0; i < cores.length; i++) {
            if (cores[i] == -2) break; //

            String val = (cores[i] == -1) ? "Sleeping / Offline" : (cores[i] / 1000) + " MHz";
            int color = (cores[i] == -1) ? Color.GRAY : Color.GREEN;

            addItem("Core " + i, val, color);
        }

        addHeader("THERMAL ZONES");
        String[] zones = thermal.split("\n");
        for (String zone : zones) {
            addItem(zone, "", Color.parseColor("#FFB74D"));
        }
    }

    private void addHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.CYAN);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, 24, 0, 8);
        container.addView(tv);
    }

    private void addItem(String label, String value, int valueColor) {
        TextView tv = new TextView(this);
        if (value.isEmpty()) {
            tv.setText(label); // Просто текст (для thermal)
        } else {
            tv.setText(label + " :  " + value);
        }
        tv.setTextColor(valueColor);
        tv.setTextSize(16);
        tv.setPadding(0, 4, 0, 4);
        container.addView(tv);
    }
}