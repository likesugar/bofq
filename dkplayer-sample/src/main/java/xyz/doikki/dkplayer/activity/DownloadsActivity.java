package xyz.doikki.dkplayer.activity;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import xyz.doikki.dkplayer.R;
import xyz.doikki.dkplayer.activity.api.PlayerActivity;
import xyz.doikki.dkplayer.util.cache.ProxyVideoCacheManager;

/**
 * 下载页：列出边播边缓存落盘的文件，点击播放，支持清空
 */
public class DownloadsActivity extends BaseActivity {

    private LinearLayout list;
    private TextView tvCap;

    @Override
    protected int getTitleResId() {
        return R.string.app_name;
    }

    @Override
    protected int getLayoutResId() {
        return 0;
    }

    @Override
    protected View getContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView btnClear = new TextView(this);
        btnClear.setText("清空缓存");
        btnClear.setTextColor(0xFFFFFFFF);
        btnClear.setTextSize(13);
        btnClear.setGravity(Gravity.CENTER);
        btnClear.setBackgroundResource(R.drawable.bg_btn);
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        lp0.setMargins(dp(12), dp(12), dp(12), dp(6));
        btnClear.setLayoutParams(lp0);
        btnClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(DownloadsActivity.this)
                        .setTitle("清空缓存")
                        .setMessage("删除全部缓存文件？")
                        .setPositiveButton("删除", (d, w) -> {
                            ProxyVideoCacheManager.clearAllCache(DownloadsActivity.this);
                            Toast.makeText(DownloadsActivity.this, "已清空", Toast.LENGTH_SHORT).show();
                            refresh();
                        })
                        .setNegativeButton("取消", null).show();
            }
        });
        root.addView(btnClear);

        // ---- 抓流控制：状态行 + 暂停/继续/停止 ----
        if (tvCap == null) {
            tvCap = new TextView(this);
            tvCap.setTextColor(0xFF333333);
            tvCap.setTextSize(13);
            tvCap.setPadding(dp(14), dp(6), dp(12), dp(2));
        }
        tvCap.setTextColor(0xFF333333);
        tvCap.setTextSize(13);
        tvCap.setPadding(dp(14), dp(6), dp(12), dp(2));

        LinearLayout capRow = new LinearLayout(this);
        capRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(0, dp(36), 1);
        brp.setMargins(dp(4), dp(4), dp(4), dp(4));
        android.widget.Button btnPause = new android.widget.Button(this);
        android.widget.Button btnResume = new android.widget.Button(this);
        android.widget.Button btnStop = new android.widget.Button(this);
        btnPause.setText("暂停"); btnResume.setText("继续"); btnStop.setText("停止");
        btnPause.setTextSize(13); btnResume.setTextSize(13); btnStop.setTextSize(13);
        btnPause.setLayoutParams(new LinearLayout.LayoutParams(brp));
        btnResume.setLayoutParams(new LinearLayout.LayoutParams(brp));
        btnStop.setLayoutParams(new LinearLayout.LayoutParams(brp));
        btnPause.setOnClickListener(v -> {
            xyz.doikki.dkplayer.util.CaptureManager.pause();
            Toast.makeText(this, "已暂停抓取", Toast.LENGTH_SHORT).show();
            refresh();
        });
        btnResume.setOnClickListener(v -> {
            xyz.doikki.dkplayer.util.CaptureManager.resume();
            Toast.makeText(this, "继续抓取", Toast.LENGTH_SHORT).show();
            refresh();
        });
        btnStop.setOnClickListener(v -> {
            xyz.doikki.dkplayer.util.CaptureManager.stop();
            Toast.makeText(this, "已停止抓取", Toast.LENGTH_SHORT).show();
            refresh();
        });
        capRow.addView(btnPause); capRow.addView(btnResume); capRow.addView(btnStop);
        root.addView(tvCap);
        root.addView(capRow);

        TextView tvTip = new TextView(this);
        tvTip.setText("文件目录: Android/data/xyz.doikki.dkplayer/files/downloads");
        tvTip.setTextColor(0xFF888888);
        tvTip.setTextSize(11);
        tvTip.setPadding(dp(12), 0, dp(12), dp(6));
        root.addView(tvTip);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        uiHandler.removeCallbacks(statusTick);
        statusTick.run();
    }

    private final android.os.Handler uiHandler = new android.os.Handler();

    private final Runnable statusTick = new Runnable() {
        public void run() {
            tvCap.setText("抓流: " + xyz.doikki.dkplayer.util.CaptureManager.statusText());
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(statusTick);
    }

    private void refresh() {
        list.removeAllViews();
        File dir = ProxyVideoCacheManager.getCacheDir(this);
        List<File> files = new ArrayList<>();
        long total = 0;
        if (dir != null && dir.isDirectory()) {
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.length() > 0) { files.add(f); total += f.length(); }
                }
            }
        }
        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        TextView tvTotal = new TextView(this);
        tvTotal.setText("共 " + files.size() + " 个 · " + fmt(total));
        tvTotal.setTextColor(0xFF333333);
        tvTotal.setTextSize(13);
        tvTotal.setPadding(dp(14), dp(6), dp(12), dp(6));
        list.addView(tvTotal);

        if (files.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("暂无缓存。播放器里点「缓存」按钮即可边播边存");
            tvEmpty.setTextColor(0xFF999999);
            tvEmpty.setTextSize(13);
            tvEmpty.setPadding(dp(14), dp(20), dp(12), dp(12));
            list.addView(tvEmpty);
            return;
        }

        for (final File f : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (list.getChildCount() > 1) lp.topMargin = dp(2);
            row.setLayoutParams(lp);
            row.setBackgroundResource(R.drawable.bg_input);

            TextView tvName = new TextView(this);
            String name = f.getName();
            if (name.endsWith(".download")) name = name.substring(0, name.length() - 9) + " (缓存中)";
            tvName.setText(name);
            tvName.setTextColor(0xFF222222);
            tvName.setTextSize(13);
            tvName.setMaxLines(1);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(tvName);

            TextView tvSize = new TextView(this);
            tvSize.setText(fmt(f.length()) + " · " + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(f.lastModified()));
            tvSize.setTextColor(0xFF888888);
            tvSize.setTextSize(11);
            row.addView(tvSize);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (f.getName().endsWith(".download")) {
                        Toast.makeText(DownloadsActivity.this, "该文件还在缓存中", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    PlayerActivity.start(DownloadsActivity.this, "file://" + f.getAbsolutePath(), f.getName(), false, false);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    new AlertDialog.Builder(DownloadsActivity.this)
                            .setTitle("删除")
                            .setMessage("删除该缓存文件？")
                            .setPositiveButton("删除", (d, w) -> { f.delete(); refresh(); })
                            .setNegativeButton("取消", null).show();
                    return true;
                }
            });
            list.addView(row);
        }
    }

    private String fmt(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.2fGB", b / 1024f / 1024 / 1024);
        if (b >= 1024L * 1024) return String.format(java.util.Locale.US, "%.1fMB", b / 1024f / 1024);
        if (b >= 1024L) return String.format(java.util.Locale.US, "%.0fKB", b / 1024f);
        return b + "B";
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
