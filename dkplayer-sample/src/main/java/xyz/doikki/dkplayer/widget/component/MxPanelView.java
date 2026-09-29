package xyz.doikki.dkplayer.widget.component;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import xyz.doikki.dkplayer.R;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videocontroller.component.GestureView;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * MX 风格播放器浮层：顶部半透明圆形按钮（默认一排5个，点击展开子选项），
 * 含 画面比例/镜像、播放速度、截图、静音、横竖屏旋转，并显示 video width/height。
 */
public class MxPanelView extends GestureView {

    private static final int[] SCALES = {
            VideoView.SCREEN_SCALE_DEFAULT,
            VideoView.SCREEN_SCALE_16_9,
            VideoView.SCREEN_SCALE_4_3,
            VideoView.SCREEN_SCALE_ORIGINAL,
            VideoView.SCREEN_SCALE_MATCH_PARENT,
            VideoView.SCREEN_SCALE_CENTER_CROP};
    private static final String[] SCALE_NAMES =
            {"默认", "16:9", "4:3", "原始", "填充", "裁剪"};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final String[] SPEED_NAMES =
            {"0.5X", "0.75X", "1X", "1.25X", "1.5X", "2X"};

    private ControlWrapper mWrapper;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView tvInfo;
    private LinearLayout rowSub;
    private LinearLayout mPanel;
    private int scaleIdx = 0, speedIdx = 2;
    private boolean mirrored = false, muted = false, landscape = false;
    private int expanded = -1;

    public MxPanelView(Context context) {
        super(context);
        init();
    }

    public MxPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @SuppressLint("SetTextI18n")
    private void init() {
        // 顶部信息 + 按钮排 + 子选项排
        mPanel = new LinearLayout(getContext());
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setPadding(10, 6, 10, 6);

        tvInfo = new TextView(getContext());
        tvInfo.setTextColor(0xFFFFFFFF);
        tvInfo.setTextSize(11);
        tvInfo.setShadowLayer(2, 1, 1, 0xFF000000);
        tvInfo.setText("video width:0 height:0");
        mPanel.addView(tvInfo);

        LinearLayout rowMain = new LinearLayout(getContext());
        rowMain.setOrientation(LinearLayout.HORIZONTAL);
        rowMain.setGravity(Gravity.CENTER_VERTICAL);
        mPanel.addView(rowMain);

        String[] mains = {"比例", "倍速", "截图", "静音", "旋转"};
        for (int i = 0; i < mains.length; i++) {
            final int idx = i;
            TextView b = circleBtn(mains[i]);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (idx == 2) { doShot(); return; }   // 截图直接执行
                    expanded = (expanded == idx) ? -1 : idx;
                    showSub();
                }
            });
            rowMain.addView(b);
        }

        rowSub = new LinearLayout(getContext());
        rowSub.setOrientation(LinearLayout.HORIZONTAL);
        rowSub.setGravity(Gravity.CENTER_VERTICAL);
        rowSub.setVisibility(GONE);
        mPanel.addView(rowSub);

        HorizontalScrollView hs = new HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(mPanel);
        addView(hs, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // 轮询刷新视频宽高
        Runnable tick = new Runnable() {
            public void run() {
                if (mWrapper != null) {
                    int[] s = mWrapper.getVideoSize();
                    if (s != null) tvInfo.setText("video width:" + s[0] + " height:" + s[1]);
                }
                mHandler.postDelayed(this, 500);
            }
        };
        mHandler.postDelayed(tick, 500);
    }

    private TextView circleBtn(String text) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.mx_circle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.rightMargin = dp(10);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView chip(String text) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.mx_circle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(36));
        lp.rightMargin = dp(6);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    @SuppressLint("SetTextI18n")
    private void showSub() {
        rowSub.removeAllViews();
        if (expanded < 0) {
            rowSub.setVisibility(GONE);
            return;
        }
        rowSub.setVisibility(VISIBLE);
        if (expanded == 0) {                       // 比例 + 镜像
            for (int i = 0; i < SCALES.length; i++) {
                final int i2 = i;
                TextView c = chip(SCALE_NAMES[i]);
                c.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        scaleIdx = i2;
                        mWrapper.setScreenScaleType(SCALES[i2]);
                        collapse();
                    }
                });
                rowSub.addView(c);
            }
            TextView mir = chip(mirrored ? "镜像开" : "镜像关");
            mir.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mirrored = !mirrored;
                    mWrapper.setMirrorRotation(mirrored);
                    collapse();
                }
            });
            rowSub.addView(mir);
        } else if (expanded == 1) {                // 倍速
            for (int i = 0; i < SPEEDS.length; i++) {
                final int i2 = i;
                TextView c = chip(SPEED_NAMES[i]);
                c.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        speedIdx = i2;
                        mWrapper.setSpeed(SPEEDS[i2]);
                        collapse();
                    }
                });
                rowSub.addView(c);
            }
        } else if (expanded == 3) {                // 静音
            TextView on = chip("静音开");
            on.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    muted = true;
                    mWrapper.setMute(true);
                    collapse();
                }
            });
            rowSub.addView(on);
            TextView off = chip("静音关");
            off.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    muted = false;
                    mWrapper.setMute(false);
                    collapse();
                }
            });
            rowSub.addView(off);
        } else if (expanded == 4) {                // 旋转
            TextView land = chip("横屏");
            land.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    landscape = true;
                    ((Activity) getContext()).setRequestedOrientation(
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    collapse();
                }
            });
            rowSub.addView(land);
            TextView port = chip("竖屏");
            port.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    landscape = false;
                    ((Activity) getContext()).setRequestedOrientation(
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    collapse();
                }
            });
            rowSub.addView(port);
        }
    }

    private void collapse() {
        expanded = -1;
        showSub();
    }

    private void doShot() {
        try {
            Bitmap bmp = mWrapper.doScreenShot();
            if (bmp == null) {
                Toast.makeText(getContext(), "当前渲染内核不支持截图", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = "dkplayer_" + System.currentTimeMillis() + ".png";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DKPlayer");
                android.net.Uri u = getContext().getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                OutputStream os = getContext().getContentResolver().openOutputStream(u);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "DKPlayer");
                if (!dir.exists()) dir.mkdirs();
                FileOutputStream fo = new FileOutputStream(new File(dir, name));
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.close();
            }
            Toast.makeText(getContext(), "已保存 Pictures/DKPlayer/" + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "截图失败:" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void attach(ControlWrapper wrapper) {
        super.attach(wrapper);
        mWrapper = wrapper;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, android.view.animation.Animation anim) {
        // 信息行与圆钮跟随控制层一起显示/隐藏（点屏幕出现，超时或再点隐藏）
        if (mPanel != null) mPanel.setVisibility(isVisible ? VISIBLE : GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
