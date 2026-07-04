package com.example.fqdownloader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.fqdownloader.jni.FqSigner;
import com.example.fqdownloader.security.Activation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE = 100;
    private TextView tvLog;
    private TextView tvProgress;
    private com.google.android.material.progressindicator.LinearProgressIndicator progressBar;
    private DownloadEngine engine;
    private File outputDir;
    private EditText etInput, etStart, etEnd;
    private boolean pendingDownloadAsTxt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Activation.isActivated(this)) {
            startActivity(new Intent(this, com.example.fqdownloader.security.ActivationActivity.class));
            finish();
            return;
        }

        Activation.checkAndRevoke(this);

        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvLog.setTextIsSelectable(true);
        tvProgress = findViewById(R.id.tv_progress);
        progressBar = findViewById(R.id.progress_bar);

        etInput = findViewById(R.id.et_input);
        etStart = findViewById(R.id.et_start);
        etEnd = findViewById(R.id.et_end);
        var btnSearch = (Button) findViewById(R.id.btn_search);
        var btnDownload = (Button) findViewById(R.id.btn_download);
        var btnDownloadTxt = (Button) findViewById(R.id.btn_download_txt);
        var btnClearCache = (Button) findViewById(R.id.btn_clear_cache);

        log("初始化签名引擎...");
        FqSigner.init(this);
        log("签名引擎就绪");

        outputDir = new File(getExternalFilesDir(null), "output");
        engine = new DownloadEngine(outputDir, new DownloadEngine.ProgressListener() {
            @Override
            public void onLog(String message) {
                runOnUiThread(() -> log(message));
            }

            @Override
            public void onProgress(int current, int total, String batchInfo) {
                runOnUiThread(() -> {
                    progressBar.setProgressCompat(current, true);
                    progressBar.setMax(total);
                    tvProgress.setText(batchInfo);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> log("错误: " + error));
            }

            @Override
            public void onComplete(String outputPath) {
                runOnUiThread(() -> {
                    log("完成! 已保存: " + outputPath);
                    copyToDownloads(outputPath);
                });
            }
        });

        btnSearch.setOnClickListener(v -> {
            String keyword = etInput.getText().toString().trim();
            if (keyword.isEmpty()) {
                log("请输入搜索关键词");
                return;
            }
            log("搜索: " + keyword);
            engine.search(keyword);
        });

        btnDownload.setOnClickListener(v -> startDownload(false));
        btnDownloadTxt.setOnClickListener(v -> startDownload(true));

        btnClearCache.setOnClickListener(v -> clearCache());
    }

    private void startDownload(boolean asTxt) {
        String bookId = etInput.getText().toString().trim();
        if (bookId.isEmpty()) {
            log("请输入 Book ID");
            return;
        }

        if (Build.VERSION.SDK_INT < 29) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadAsTxt = asTxt;
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
                return;
            }
        }

        doDownload(bookId, asTxt);
    }

    private void doDownload(String bookId, boolean asTxt) {
        int start = 1, end = 0;
        try {
            if (!etStart.getText().toString().trim().isEmpty())
                start = Integer.parseInt(etStart.getText().toString().trim());
            if (!etEnd.getText().toString().trim().isEmpty())
                end = Integer.parseInt(etEnd.getText().toString().trim());
        } catch (NumberFormatException e) {
            log("章节号格式错误");
            return;
        }

        log("开始下载: " + bookId + " (章节 " + start + "-" + (end > 0 ? end : "全部") + ")");
        engine.download(bookId, start, end, asTxt);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            String bookId = etInput.getText().toString().trim();
            if (!bookId.isEmpty()) doDownload(bookId, pendingDownloadAsTxt);
        } else {
            log("存储权限被拒绝，无法复制到 Download 目录");
        }
    }

    private void copyToDownloads(String filePath) {
        try {
            File src = new File(filePath);
            if (!src.exists()) return;
            String fileName = src.getName();

            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, fileName.endsWith(".txt") ? "text/plain" : "application/epub+zip");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                var uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri);
                         FileInputStream fis = new FileInputStream(src)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    log("已复制到: Download/" + fileName);
                }
            } else {
                File dstDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File dst = new File(dstDir, fileName);
                try (FileInputStream fis = new FileInputStream(src);
                     FileOutputStream fos = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                }
                log("已复制到: Download/" + fileName);
            }
        } catch (Exception e) {
            log("复制到 Download 失败: " + e);
        }
    }

    private void clearCache() {
        new Thread(() -> {
            try {
                deleteRecursive(outputDir);
                deleteRecursive(new File(getFilesDir(), "lib"));
                runOnUiThread(() -> log("缓存已清除"));
            } catch (Exception e) {
                runOnUiThread(() -> log("清除缓存失败: " + e));
            }
        }).start();
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private void log(String msg) {
        Log.d("MainActivity", msg);
        tvLog.append(msg + "\n");
        var layout = tvLog.getLayout();
        if (layout != null) {
            int scrollAmount = layout.getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scrollAmount > 0) tvLog.scrollTo(0, scrollAmount);
        }
    }
}
