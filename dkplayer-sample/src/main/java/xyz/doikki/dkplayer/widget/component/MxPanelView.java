package xyz.doikki.dkplayer.widget.component;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 * B站风格播放器浮层：右上角 ⁝ 按钮，点开右侧半透明功能面板
 * （图标排/播放方式/画面尺寸/播放速度/工具），信息行显示 video width/height
 */
public class MxPanelView extends GestureView {

    private static final int[] SCALES = {
            VideoView.SCREEN_SCALE_DEFAULT,
            VideoView.SCREEN_SCALE_MATCH_PARENT,
            VideoView.SCREEN_SCALE_CENTER_CROP,
            VideoView.SCREEN_SCALE_16_9,
            VideoView.SCREEN_SCALE_4_3};
    private static final String[] SCALE_NAMES =
            {"适应", "拉伸", "填充", "16:9", "4:3"};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final String[] SPEED_NAMES =
            {"0.5X", "0.75X", "1X", "1.25X", "1.5X", "2X"};

    private ControlWrapper mWrapper;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView tvInfo;
    private LinearLayout mMenu;
    private TextView btnMore;
    private int scaleIdx = 0, speedIdx = 2;
    private boolean mirrored = false, muted = false, loopOn = false, tinyOn = false;

    /** 由播放页注入：缓存实时抓流 / 代理兜底 / 循环（DK 无内置循环，播放页在完成事件里重播） */
    public Runnable onCacheClick;
    public Runnable onProxyClick;
    public interface MenuAction { void onLoop(boolean loopOn); }
    public MenuAction menuAction;
    /** 第四版「其他地址→开始播放」，与控制层合并 */
    public interface UrlAction { void onPlay(String url); }
    public UrlAction urlAction;

    private static final int PINK = 0xFFFF6699;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    public MxPanelView(Context context) {
        super(context);
        init();
    }

    public MxPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private TextView item(String text, int color, float size) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(size);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(10), dp(6), dp(10));
        return b;
    }

    private TextView menuItem(final String text, final Runnable action) {
        TextView b = item(text, WHITE, 14);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (action != null) action.run(); }
        });
        return b;
    }

    private LinearLayout groupTitle(String text) {
        LinearLayout g = new LinearLayout(getContext());
        g.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = item(text, GRAY, 13);
        g.addView(t);
        return g;
    }

    /** 一行若干等宽选项，选中变粉 */
    private LinearLayout optionRow(String[] names, final int checkedIdx, final OnPick pick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView b = item(names[i], idx == checkedIdx ? PINK : WHITE, 14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = dp(4);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pick.onPick(idx); }
            });
            row.addView(b);
        }
        return row;
    }

    private interface OnPick { void onPick(int idx); }

    @SuppressLint("SetTextI18n")
    private void init() {
        // ---- 右侧功能面板（默认隐藏） ----
        mMenu = new LinearLayout(getContext());
        mMenu.setOrientation(LinearLayout.VERTICAL);
        mMenu.setBackgroundColor(0xD9101010);
        mMenu.setPadding(dp(14), dp(10), dp(14), dp(14));

        // 图标排（文字替代图标）
        LinearLayout icons = new LinearLayout(getContext());
        icons.setOrientation(LinearLayout.HORIZONTAL);
        icons.addView(menuItem("后台播放", new Runnable() { public void run() {
            Toast.makeText(getContext(), "后台播放暂未支持", Toast.LENGTH_SHORT).show(); }}));
        icons.addView(menuItem(mirrored ? "镜像已开" : "镜像翻转", new Runnable() { public void run() {
            mirrored = !mirrored;
            mWrapper.setMirrorRotation(mirrored);
        }}));
        icons.addView(menuItem(tinyOn ? "退出小窗" : "小窗播放", new Runnable() { public void run() {
            tinyOn = !tinyOn;
            if (tinyOn) mWrapper.startTinyScreen(); else mWrapper.stopTinyScreen();
        }}));
        icons.addView(menuItem("定时关闭", new Runnable() { public void run() {
            Toast.makeText(getContext(), "定时关闭暂未支持", Toast.LENGTH_SHORT).show(); }}));
        mMenu.addView(icons);
        mMenu.addView(sep());

        // 播放方式
        mMenu.addView(groupTitle("播放方式"));
        mMenu.addView(optionRow(new String[]{"单集循环", "播完暂停"}, 1, new OnPick() {
            public void onPick(int idx) {
                loopOn = idx == 0;
                if (menuAction != null) menuAction.onLoop(loopOn);
                Toast.makeText(getContext(), loopOn ? "单集循环已开启" : "播完暂停", Toast.LENGTH_SHORT).show();
            }
        }));
        mMenu.addView(sep());

        // 画面尺寸
        mMenu.addView(groupTitle("画面尺寸"));
        mMenu.addView(optionRow(SCALE_NAMES, scaleIdx, new OnPick() {
            public void onPick(int idx) {
                scaleIdx = idx;
                mWrapper.setScreenScaleType(SCALES[idx]);
            }
        }));
        mMenu.addView(sep());

        // 播放速度
        mMenu.addView(groupTitle("播放速度"));
        mMenu.addView(optionRow(SPEED_NAMES, speedIdx, new OnPick() {
            public void onPick(int idx) {
                speedIdx = idx;
                mWrapper.setSpeed(SPEEDS[idx]);
            }
        }));
        mMenu.addView(sep());

        // 工具
        mMenu.addView(groupTitle("工具"));
        mMenu.addView(optionRow(new String[]{"缓存抓流", "代理兜底"}, -1, new OnPick() {
            public void onPick(int idx) {
                if (idx == 0 && onCacheClick != null) onCacheClick.run();
                if (idx == 1 && onProxyClick != null) onProxyClick.run();
            }
        }));

        ScrollView sv = new ScrollView(getContext());
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(mMenu);
        LayoutParams mlp = new LayoutParams(dp(300), LayoutParams.MATCH_PARENT, Gravity.END);
        mMenu.setVisibility(GONE);
        addView(sv, mlp);

        // ---- 顶部：信息行(video width/height) + 右上角 ⁝ ----
        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(6), dp(10), dp(6));

        tvInfo = new TextView(getContext());
        tvInfo.setTextColor(WHITE);
        tvInfo.setTextSize(11);
        tvInfo.setShadowLayer(2, 1, 1, 0xFF000000);
        tvInfo.setText("video width:0 height:0");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvInfo.setLayoutParams(ilp);
        top.addView(tvInfo);

        btnMore = new TextView(getContext());
        btnMore.setText("⁝");
        btnMore.setTextColor(WHITE);
        btnMore.setTextSize(22);
        btnMore.setGravity(Gravity.CENTER);
        btnMore.setBackgroundResource(R.drawable.mx_circle);
        btnMore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean opening = mMenu.getVisibility() != VISIBLE;
                mMenu.setVisibility(opening ? VISIBLE : GONE);
                if (mWrapper == null) return;
                if (opening) {
                    mWrapper.stopFadeOut();      // 面板打开期间不让控制层自动隐藏
                    startKeepAlive();
                } else {
                    stopKeepAlive();
                    mWrapper.startFadeOut();
                }
            }
        });
        top.addView(btnMore, new LinearLayout.LayoutParams(dp(40), dp(40)));

        addView(top, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT, Gravity.TOP));

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

    private View sep() {
        View v = new View(getContext());
        v.setBackgroundColor(0x33FFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        ((LinearLayout.LayoutParams) v.getLayoutParams()).topMargin = dp(10);
        ((LinearLayout.LayoutParams) v.getLayoutParams()).bottomMargin = dp(10);
        return v;
    }

    private void doShot() {
        try {
            Bitmap bmp = mWrapper.doScreenShot();
            if (bmp == null) {
                Toast.makeText(getContext(), "当前渲染内核不支持截图", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = "dkplayer_" + System.currentTimeMillis() + ".png";
            if (android.os.Build.VERSION.SDK_INT >= 29) {
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

    /** 面板打开期间保活控制层显示 */
    private final Runnable keepAlive = new Runnable() {
        public void run() {
            if (mMenu != null && mMenu.getVisibility() == VISIBLE && mWrapper != null) {
                mWrapper.show();
                mHandler.postDelayed(this, 3000);
            }
        }
    };

    private void startKeepAlive() {
        mHandler.removeCallbacks(keepAlive);
        mHandler.postDelayed(keepAlive, 3000);
    }

    private void stopKeepAlive() {
        mHandler.removeCallbacks(keepAlive);
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, android.view.animation.Animation anim) {
        // 信息行与 ⁝ 跟随控制层显示/隐藏；隐藏时收起面板并停保活
        if (isVisible) {
            btnMore.setVisibility(VISIBLE);
            tvInfo.setVisibility(VISIBLE);
        } else {
            btnMore.setVisibility(GONE);
            tvInfo.setVisibility(GONE);
            mMenu.setVisibility(GONE);
            stopKeepAlive();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
