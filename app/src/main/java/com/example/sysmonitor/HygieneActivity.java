package com.example.sysmonitor;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HygieneActivity extends AppCompatActivity {

    // Шаги порога: 10MB, 25MB, 50MB, 100MB, 250MB, 500MB, 1GB, 2GB, 5GB, 10GB
    private static final long[] THRESHOLD_VALUES = {
            10L  * 1024 * 1024,
            25L  * 1024 * 1024,
            50L  * 1024 * 1024,
            100L * 1024 * 1024,
            250L * 1024 * 1024,
            500L * 1024 * 1024,
            1024L * 1024 * 1024,
            2L   * 1024 * 1024 * 1024,
            5L   * 1024 * 1024 * 1024,
            10L  * 1024 * 1024 * 1024
    };
    private static final String[] THRESHOLD_LABELS = {
            "10 MB", "25 MB", "50 MB", "100 MB", "250 MB",
            "500 MB", "1 GB", "2 GB", "5 GB", "10 GB"
    };

    private TextView textStatus, textLargeFiles, textEmptyFolders;
    private TextView textThresholdValue, arrowLargeFiles;
    private ProgressBar progressBar;
    private Button btnScan, btnCleanLarge, btnCleanEmpty;
    private SeekBar seekBarThreshold;
    private LinearLayout listLargeFiles, headerLargeFiles;

    private long currentThreshold = 100L * 1024 * 1024; // default 100 MB
    private boolean isScanning = false;
    private boolean filesListExpanded = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hygiene);

        // Отступ от шторки
        View root = findViewById(R.id.hygieneRootScroll);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                v.setPadding(v.getPaddingLeft(), top + 16,
                        v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
        }

        bindViews();
        setupThresholdSeekBar();
        updateHealthScore();

        btnScan.setOnClickListener(v -> checkPermissionAndScan());

        // Раскрытие/скрытие списка файлов
        headerLargeFiles.setOnClickListener(v -> toggleFilesList());
    }

    private void bindViews() {
        textStatus        = findViewById(R.id.textStatus);
        textLargeFiles    = findViewById(R.id.textLargeFiles);
        textEmptyFolders  = findViewById(R.id.textEmptyFolders);
        progressBar       = findViewById(R.id.progressBar);
        btnScan           = findViewById(R.id.btnScan);
        btnCleanLarge     = findViewById(R.id.btnCleanLarge);
        btnCleanEmpty     = findViewById(R.id.btnCleanEmpty);
        seekBarThreshold  = findViewById(R.id.seekBarThreshold);
        textThresholdValue= findViewById(R.id.textThresholdValue);
        listLargeFiles    = findViewById(R.id.listLargeFiles);
        headerLargeFiles  = findViewById(R.id.headerLargeFiles);
        arrowLargeFiles   = findViewById(R.id.arrowLargeFiles);
    }

    private void setupThresholdSeekBar() {
        // Начальная позиция — 100 MB (индекс 3)
        seekBarThreshold.setProgress(3);
        currentThreshold = THRESHOLD_VALUES[3];
        textThresholdValue.setText(THRESHOLD_LABELS[3]);

        seekBarThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentThreshold = THRESHOLD_VALUES[progress];
                textThresholdValue.setText(THRESHOLD_LABELS[progress]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void toggleFilesList() {
        filesListExpanded = !filesListExpanded;
        listLargeFiles.setVisibility(filesListExpanded ? View.VISIBLE : View.GONE);
        arrowLargeFiles.setText(filesListExpanded ? "▲" : "▼");
    }

    private void checkPermissionAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Grant 'All Files Access' to scan junk", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception ex) {
                        Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show();
                    }
                }
                return;
            }
        }
        if (!isScanning) startScan();
    }

    private void startScan() {
        isScanning = true;
        btnScan.setEnabled(false);
        btnCleanLarge.setVisibility(View.GONE);
        btnCleanEmpty.setVisibility(View.GONE);
        listLargeFiles.removeAllViews();
        listLargeFiles.setVisibility(View.GONE);
        filesListExpanded = false;
        arrowLargeFiles.setText("▼");

        progressBar.setVisibility(View.VISIBLE);
        textStatus.setText("Scanning...");
        textLargeFiles.setText("Large Files: scanning...");
        textEmptyFolders.setText("Empty Folders: scanning...");

        final long threshold = currentThreshold;

        executor.execute(() -> {
            List<File> largeFiles    = new ArrayList<>();
            List<File> emptyFolders  = new ArrayList<>();
            long largeFilesSize = 0;

            try {
                File root = Environment.getExternalStorageDirectory();
                Stack<File> stack = new Stack<>();
                stack.push(root);

                while (!stack.isEmpty()) {
                    File current = stack.pop();
                    if (current == null || !current.isDirectory()) continue;

                    File[] files = current.listFiles();
                    if (files != null && files.length > 0) {
                        for (File f : files) {
                            if (f.isDirectory()) {
                                stack.push(f);
                            } else if (f.isFile() && f.length() >= threshold) {
                                largeFiles.add(f);
                                largeFilesSize += f.length();
                            }
                        }
                    } else if (files != null) {
                        // Пустая папка — пропускаем системные
                        String path = current.getAbsolutePath();
                        if (!path.contains("/Android/data") &&
                                !path.contains("/Android/obb") &&
                                !path.contains("/DCIM") &&
                                !path.contains("/Pictures") &&
                                !path.contains("/Download")) {
                            emptyFolders.add(current);
                        }
                    }
                }

                // Сортируем по размеру (самые большие вверху)
                largeFiles.sort((a, b) -> Long.compare(b.length(), a.length()));

                long finalSize = largeFilesSize;
                List<File> finalLarge  = largeFiles;
                List<File> finalEmpty  = emptyFolders;

                runOnUiThread(() -> {
                    textStatus.setText("Status: Scan complete ✓");
                    progressBar.setVisibility(View.GONE);
                    btnScan.setEnabled(true);
                    isScanning = false;

                    // Large files
                    if (finalLarge.isEmpty()) {
                        textLargeFiles.setText("Large Files: None found ✓");
                        textLargeFiles.setTextColor(0xFF4CAF50);
                        arrowLargeFiles.setVisibility(View.GONE);
                    } else {
                        String label = finalLarge.size() + " files · "
                                + Formatter.formatFileSize(this, finalSize);
                        textLargeFiles.setText("Large Files: " + label);
                        textLargeFiles.setTextColor(0xFFFF9800);
                        arrowLargeFiles.setVisibility(View.VISIBLE);

                        // Заполняем список
                        listLargeFiles.removeAllViews();
                        int limit = Math.min(finalLarge.size(), 50); // не больше 50 строк
                        for (int i = 0; i < limit; i++) {
                            File f = finalLarge.get(i);
                            TextView tv = new TextView(this);
                            String name = f.getName();
                            String size = Formatter.formatFileSize(this, f.length());
                            String dir  = f.getParent() != null
                                    ? f.getParent().replace(
                                    Environment.getExternalStorageDirectory().getPath(), "…")
                                    : "";
                            tv.setText("📄 " + name + "  [" + size + "]\n   " + dir);
                            tv.setTextColor(Color.parseColor("#CCCCCC"));
                            tv.setTextSize(12f);
                            tv.setPadding(0, 6, 0, 6);
                            listLargeFiles.addView(tv);

                            // Разделитель
                            if (i < limit - 1) {
                                View divider = new View(this);
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                                divider.setLayoutParams(lp);
                                divider.setBackgroundColor(Color.parseColor("#333333"));
                                listLargeFiles.addView(divider);
                            }
                        }
                        if (finalLarge.size() > 50) {
                            TextView more = new TextView(this);
                            more.setText("…and " + (finalLarge.size() - 50) + " more files");
                            more.setTextColor(Color.parseColor("#888888"));
                            more.setTextSize(12f);
                            more.setPadding(0, 8, 0, 0);
                            listLargeFiles.addView(more);
                        }

                        btnCleanLarge.setVisibility(View.VISIBLE);
                        btnCleanLarge.setOnClickListener(v -> cleanLargeFiles(finalLarge));
                    }

                    // Empty folders
                    if (finalEmpty.isEmpty()) {
                        textEmptyFolders.setText("Empty Folders: None found ✓");
                        textEmptyFolders.setTextColor(0xFF4CAF50);
                    } else {
                        textEmptyFolders.setText("Empty Folders: " + finalEmpty.size() + " found");
                        textEmptyFolders.setTextColor(0xFFFF9800);
                        btnCleanEmpty.setVisibility(View.VISIBLE);
                        btnCleanEmpty.setOnClickListener(v -> cleanEmptyFolders(finalEmpty));
                    }

                    updateHealthScore();
                });

            } catch (SecurityException e) {
                runOnUiThread(() -> {
                    textStatus.setText("Status: Permission denied");
                    Toast.makeText(this, "Grant storage permission", Toast.LENGTH_LONG).show();
                    progressBar.setVisibility(View.GONE);
                    btnScan.setEnabled(true);
                    isScanning = false;
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    textStatus.setText("Status: Error — " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                    btnScan.setEnabled(true);
                    isScanning = false;
                });
            }
        });
    }

    private void cleanLargeFiles(List<File> files) {
        if (files.isEmpty()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete Large Files")
                .setMessage("Delete " + files.size() + " files? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> executor.execute(() -> {
                    int deleted = 0;
                    long freed = 0;
                    for (File f : files) {
                        try {
                            if (f.exists() && f.canWrite()) {
                                freed += f.length();
                                if (f.delete()) deleted++;
                            }
                        } catch (Exception e) {
                            Log.w("HygieneActivity", "Delete failed: " + f.getPath(), e);
                        }
                    }
                    int fd = deleted; long ff = freed;
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Deleted " + fd + " files, freed "
                                + Formatter.formatFileSize(this, ff), Toast.LENGTH_LONG).show();
                        CleanManager.saveCleanTime(this);
                        updateHealthScore();
                        startScan();
                    });
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void cleanEmptyFolders(List<File> folders) {
        if (folders.isEmpty()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete Empty Folders")
                .setMessage("Delete " + folders.size() + " empty folders?")
                .setPositiveButton("Delete", (d, w) -> executor.execute(() -> {
                    folders.sort((a, b) -> Integer.compare(
                            b.getAbsolutePath().split("/").length,
                            a.getAbsolutePath().split("/").length));
                    int deleted = 0;
                    for (File f : folders) {
                        try {
                            if (f.exists() && f.isDirectory()
                                    && f.list() != null && f.list().length == 0) {
                                if (f.delete()) deleted++;
                            }
                        } catch (Exception e) {
                            Log.w("HygieneActivity", "Folder delete failed: " + f.getPath(), e);
                        }
                    }
                    int fd = deleted;
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Deleted " + fd + " folders", Toast.LENGTH_SHORT).show();
                        CleanManager.saveCleanTime(this);
                        updateHealthScore();
                        startScan();
                    });
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateHealthScore() {
        TextView tv = findViewById(R.id.textHealthScoreHygiene);
        if (tv == null) return;
        int health = CleanManager.getSystemHealthScore(this);
        tv.setText("System Health: " + health + "%");
        if (health >= 80)      tv.setTextColor(0xFF4CAF50);
        else if (health >= 50) tv.setTextColor(0xFFFFB74D);
        else                   tv.setTextColor(0xFFFF5252);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}