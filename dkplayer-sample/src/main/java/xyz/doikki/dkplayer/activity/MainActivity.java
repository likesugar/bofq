package xyz.doikki.dkplayer.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.doikki.dkplayer.R;
import xyz.doikki.dkplayer.activity.api.ParallelPlayActivity;
import xyz.doikki.dkplayer.activity.api.PlayerActivity;
import xyz.doikki.dkplayer.activity.api.PlayRawAssetsActivity;
import xyz.doikki.dkplayer.activity.extend.ADActivity;
import xyz.doikki.dkplayer.activity.extend.CacheActivity;
import xyz.doikki.dkplayer.activity.extend.CustomExoPlayerActivity;
import xyz.doikki.dkplayer.activity.extend.CustomIjkPlayerActivity;
import xyz.doikki.dkplayer.activity.extend.DefinitionPlayerActivity;
import xyz.doikki.dkplayer.activity.extend.FullScreenActivity;
import xyz.doikki.dkplayer.activity.extend.PadActivity;
import xyz.doikki.dkplayer.activity.extend.PlayListActivity;
import xyz.doikki.dkplayer.activity.list.tiktok.TikTokActivity;
import xyz.doikki.dkplayer.activity.pip.PIPActivity;
import xyz.doikki.dkplayer.util.BiliProxy;
import xyz.doikki.dkplayer.util.DlnaRenderer;

/**
 * 首页（照抖音直播解析源码样式）：
 * 顶栏 = 输入框 + 解析 + 切电脑UA + 记录；解析 = 哔抖解析（自小工具 1:1 移植）
 * 低栏 = 功能大全 + 播放器；右下角悬浮球切换顶栏
 */
public class MainActivity extends Activity {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+|bilibili://[^\\s]+");
    private static final Pattern BILI_VIDEO = Pattern.compile("bilibili://(?:video|bangumi|story)/([0-9]+)");
    private static final Pattern BILI_LIVE = Pattern.compile("bilibili://live/(\\d+)");

    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private EditText etInput;
    private LinearLayout list;
    private final Set<String> seenMedia = new HashSet<>();
    private final java.util.List<String> douyinCands = new java.util.ArrayList<>();
    private boolean douyinPickScheduled = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean mobileUA = true;
    private int recordId = 0;

    private WebView bgWeb = null;
    private String pendingBili = null;
    private boolean parsing = false;
    private boolean bgDone = false;
    private boolean autoPlayed = false;
    private final java.util.List<String> autoCandidates = new java.util.ArrayList<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BiliProxy.start();
        DlnaRenderer.start(this);   // 接收其他App投屏(DLNA)

        etInput = findViewById(R.id.et_url);
        list = findViewById(R.id.layout_records);

        etInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        etInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                    triggerParse();
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.btn_parse).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { triggerParse(); }
        });
        findViewById(R.id.btn_ua).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mobileUA = !mobileUA;
                ((TextView) findViewById(R.id.btn_ua)).setText(mobileUA ? "切电脑UA" : "切手机UA");
                if (bgWeb != null) bgWeb.getSettings().setUserAgentString(mobileUA ? UA_MOBILE : UA_DESKTOP);
            }
        });
        findViewById(R.id.btn_records).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View p = findViewById(R.id.bottom_panel);
                p.setVisibility(p.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        findViewById(R.id.btn_toggle_top).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View t = findViewById(R.id.top_bar);
                t.setVisibility(t.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        findViewById(R.id.btn_features).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showFeatures(); }
        });
        findViewById(R.id.btn_player).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openStandalonePlayer(); }
        });

        // 外部直接带链接进来
        String pre = getIntent().getStringExtra("url");
        if (pre != null && !pre.isEmpty()) {
            etInput.setText(pre);
            triggerParse();
        }
    }

    /** 单独播放器：剪贴板有链接直接播；没有也直接进（页面上用其他地址开始播放） */
    private void openStandalonePlayer() {
        String url = null;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                if (t != null && t.toString().trim().startsWith("http")) url = t.toString().trim();
            }
        } catch (Throwable ignored) {}
        // 无链接也进播放器：页面下方「其他地址+开始播放」可直接用
        PlayerActivity.start(this, url, "播放器", false, false);
    }

    /** 功能大全：原 sample 演示功能列表 */
    private void showFeatures() {
        final String[] names = {"下载页", "全屏播放", "播放列表", "抖音上下滑", "画中画", "自定义Exo内核",
                "自定义IJK内核", "多清晰度", "边播边缓存", "播放广告样例", "Pad适配",
                "并行播放", "Raw Assets", "CPU信息"};
        final Class<?>[] cls = {DownloadsActivity.class, FullScreenActivity.class, PlayListActivity.class, TikTokActivity.class,
                PIPActivity.class, CustomExoPlayerActivity.class, CustomIjkPlayerActivity.class,
                DefinitionPlayerActivity.class, CacheActivity.class, ADActivity.class, PadActivity.class,
                ParallelPlayActivity.class, PlayRawAssetsActivity.class, CpuInfoActivity.class};
        new AlertDialog.Builder(this)
                .setTitle("功能大全")
                .setItems(names, (d, w) -> startActivity(new Intent(this, cls[w])))
                .show();
    }

    // ==================== 哔抖解析（自小工具 ParseActivity 1:1 移植） ====================

    private void triggerParse() {
        if (parsing) {
            stopParse();
            return;
        }
        String raw = etInput.getText().toString().trim();
        if (raw.length() == 0) return;
        String url = normInput(raw);
        if (url == null) return;

        parsing = true;
        ((TextView) findViewById(R.id.btn_parse)).setText("停止");
        findViewById(R.id.bottom_panel).setVisibility(View.VISIBLE);

        if (isStreamUrl(url)) { addRecord("直播流", url); parseDone(); return; }

        if (url.contains("bilibili.com") || url.contains("b23.tv")) {
            ensureBgWeb(true);   // B站解析过程可见
            resolveBiliViaPeanut(url);
        } else if (url.contains("live.douyin.com")) {
            // 抖音直播：大屏浏览页打开(可见,自带过风控种Cookie)，抓到流自动播
            synchronized (douyinCands) { douyinCands.clear(); }
            douyinPickScheduled = false;
            ensureBgWeb(true);
            bgWeb.loadUrl(url);
            startDouyinWatchdog();   // 没抓到裸地址就一直刷新重试
        } else {
            synchronized (douyinCands) { douyinCands.clear(); }
            douyinPickScheduled = false;
            ensureBgWeb(true);   // 大屏浏览页（照抖音直播解析源码：可见WebView+嗅探）
            bgWeb.loadUrl(url);
        }
    }

    private void stopParse() {
        parsing = false;
        ((TextView) findViewById(R.id.btn_parse)).setText("解析");
        if (bgWeb != null) {
            bgWeb.stopLoading();
            pendingBili = null;
        }
    }

    private void parseDone() {
        if (!parsing) return;
        parsing = false;
        ((TextView) findViewById(R.id.btn_parse)).setText("解析");
        // 浏览页常驻不收起（照源码）
    }

    private String normInput(String raw) {
        raw = raw.trim();
        String url = extractUrl(raw);
        if (url == null) {
            if (raw.matches("\\d+")) url = "https://live.douyin.com/" + raw;
            else return null;
        }
        if (url.startsWith("bilibili://")) {
            url = biliSchemeToWeb(url);
            if (url == null) return null;
        }
        if (!url.startsWith("http")) url = "https://" + url;
        return url;
    }

    private boolean isStreamUrl(String url) {
        String l = url.toLowerCase();
        return l.endsWith(".flv") || l.endsWith(".m3u8") || l.endsWith(".ts")
            || l.contains(".flv?") || l.contains(".m3u8?");
    }

    private String extractUrl(String text) {
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) {
            String u = m.group();
            return u.replaceAll("[.,;:!?]+$", "").replaceFirst("&amp;", "&");
        }
        return null;
    }

    private String biliSchemeToWeb(String url) {
        Matcher lv = BILI_LIVE.matcher(url);
        if (lv.find()) return "https://live.bilibili.com/" + lv.group(1);
        Matcher mv = BILI_VIDEO.matcher(url);
        if (mv.find()) return "https://www.bilibili.com/video/av" + mv.group(1);
        return null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureBgWeb() {
        ensureBgWeb(false);
    }

    /** visible=true 时全屏展示解析网页（B站 hellotik 过程可见） */
    @SuppressLint("SetJavaScriptEnabled")
    private void ensureBgWeb(boolean fullscreen) {
        if (bgWeb != null) {
            if (fullscreen) showParseWeb(); else bgWeb.setVisibility(View.GONE);
            return;
        }
        bgWeb = new WebView(this);
        WebSettings s = bgWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(mobileUA ? UA_MOBILE : UA_DESKTOP);
        CookieManager.getInstance().setAcceptThirdPartyCookies(bgWeb, true);
        final Handler mh2 = new Handler(Looper.getMainLooper());
        final Runnable muteTask2 = new Runnable() {
            public void run() {
                if (bgWeb == null) return;
                bgWeb.evaluateJavascript(
                    "(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)m[i].muted=true;})()", null);
                mh2.postDelayed(this, 1200);
            }
        };
        mh2.postDelayed(muteTask2, 500);
        bgWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 油猴脚本：抖音页面自动注入「抖音网页版全能优化」
                if (url != null && (url.contains("douyin.com") || url.contains("iesdouyin"))) {
                    injectUserscript(view);
                }
                if (url != null && url.contains("hellotik.app")) {
                    view.postDelayed(new Runnable() {
                        public void run() { hellotikSubmit(0); }
                    }, 500);
                }
                // 抖音直播：页面种好真Cookie后，用 extractor 方案兜底解析
                if (url != null && url.contains("live.douyin.com") && parsing) {
                    view.postDelayed(new Runnable() {
                        public void run() {
                            if (!douyinCands.isEmpty() || douyinPickScheduled) return;
                            String ck = CookieManager.getInstance().getCookie("https://live.douyin.com");
                            douyinLiveExtract(url, ck);
                        }
                    }, 3000);
                }
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                maybeRecordBiliMedia(url);
                String l = url.toLowerCase();
                if (url.contains("/log/")) return null;
                // 抖音直播：只要无参数裸地址 .../stage/xxxxx（不带 .flv?e= 签名参数，签名地址每次都变会死循环）
                String base = url;
                int qi = base.indexOf('?');
                if (qi > 0) base = base.substring(0, qi);
                String lb = base.toLowerCase();
                boolean bareLive = lb.contains("douyincdn") && lb.contains("/stage/")
                    && !lb.substring(lb.lastIndexOf('/') + 1).contains(".");
                if (bareLive && !l.contains("bilivideo") && !l.contains("upos-")) {
                    final String f = base;
                    boolean added = false;
                    synchronized (douyinCands) {
                        if (!douyinCands.contains(f)) { douyinCands.add(f); added = true; }
                    }
                    if (added && !douyinPickScheduled) {
                        douyinPickScheduled = true;
                        main.postDelayed(new Runnable() { public void run() { finishDouyinPick(); } }, 3000);
                    }
                    return null;
                }
                boolean hit = (l.contains(".flv") || l.contains(".m3u8") || l.contains(".mp4") || l.contains(".m4s"))
                    && (l.contains("douyinvod") || l.contains("/aweme/v1/play") || l.contains("playwm"));
                if (hit && !l.contains("bilivideo") && !l.contains("upos-")) {
                    // 抖音强制最高画质：ratio→1080p；biz_resolution→1088x1920（兼容 %3D 编码）
                    String hi = url
                        .replaceAll("ratio=[a-zA-Z0-9_]+", "ratio=1080p")
                        .replaceAll("biz_resolution(=|%3D|%3d)[a-zA-Z0-9_x]+", "biz_resolution$11088x1920")
                        .replaceAll("(?<!biz_)resolution(=|%3D|%3d)[a-zA-Z0-9_x]+", "resolution$11088x1920");
                    final String f = hi;
                    synchronized (douyinCands) {
                        if (!douyinCands.contains(f)) douyinCands.add(f);
                    }
                    if (!douyinPickScheduled) {
                        douyinPickScheduled = true;
                        main.postDelayed(new Runnable() { public void run() { finishDouyinPick(); } }, 6000);
                    }
                    if (l.contains(".m3u8")) {
                        new Thread(new Runnable() { public void run() { pickBestVariant(url); } }).start();
                    }
                }
                return null;
            }
        });
        android.view.ViewGroup content = (android.view.ViewGroup) findViewById(android.R.id.content);
        if (fullscreen) {
            showParseWeb();
        } else {
            content.addView(bgWeb, new android.view.ViewGroup.LayoutParams(1, 1));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 把浏览页嵌入主页容器常驻显示（不收起，照源码） */
    private void showParseWeb() {
        android.view.ViewGroup container = (android.view.ViewGroup) findViewById(R.id.web_container);
        if (bgWeb.getParent() == container) return;
        if (bgWeb.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) bgWeb.getParent()).removeView(bgWeb);
        }
        container.addView(bgWeb, new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        bgWeb.setVisibility(View.VISIBLE);
    }

    /** HLS 主清单择优：抓 BANDWIDTH 最大的变体流进记录（自小工具 DouyinActivity 移植） */
    private void pickBestVariant(String masterUrl) {
        try {
            HttpURLConnection c = (HttpURLConnection) new java.net.URL(masterUrl).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA_MOBILE);
            c.setRequestProperty("Referer", "https://live.douyin.com/");
            if (c.getResponseCode() != 200) return;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close(); c.disconnect();
            String body = bo.toString("UTF-8");
            if (!body.contains("#EXT-X-STREAM-INF")) return;   // 已是媒体清单
            long bestBw = -1; String best = null; long curBw = -1;
            java.net.URI base = java.net.URI.create(masterUrl);
            for (String ln : body.split("\n")) {
                String t = ln.trim();
                if (t.startsWith("#EXT-X-STREAM-INF")) {
                    Matcher bm = Pattern.compile("BANDWIDTH=(\\d+)").matcher(t);
                    curBw = bm.find() ? Long.parseLong(bm.group(1)) : -1;
                } else if (!t.isEmpty() && !t.startsWith("#") && curBw > bestBw) {
                    bestBw = curBw;
                    best = base.resolve(t).toString();
                }
            }
            if (best != null && seenMedia.add(best)) {
                final String f = best;
                synchronized (douyinCands) {
                    if (!douyinCands.contains(f)) douyinCands.add(f);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void resolveBiliViaPeanut(String biliUrl) {
        pendingBili = biliUrl;
        bgDone = false;
        seenMedia.clear();
        autoPlayed = false;
        ensureBgWeb();
        bgWeb.loadUrl("https://www.hellotik.app/zh/bilibili");
    }

    /** hellotik 流程①：填入链接并点「解析视频」 */
    private void hellotikSubmit(final int round) {
        if (pendingBili == null || bgWeb == null) return;
        final String bili = pendingBili.replace("'", "");
        String js = "(function(){"
            + "var inp=null,all=document.querySelectorAll('input[type=text],input[type=url],input');"
            + "for(var i=0;i<all.length;i++){"
            + "var p=(all[i].getAttribute('placeholder')||'');"
            + "if(p.indexOf('请粘贴视频链接')>=0){inp=all[i];break;}"
            + "}"
            + "if(!inp){inp=document.querySelector('input[type=url]')||document.querySelector('input');}"
            + "if(!inp){return 'noinp';}"
            + "var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');"
            + "d.set.call(inp,'" + bili + "');"
            + "inp.dispatchEvent(new Event('input',{bubbles:true}));"
            + "setTimeout(function(){"
            + "var bs=document.getElementsByTagName('button');"
            + "for(var i=0;i<bs.length;i++){"
            + "if(bs[i].textContent.indexOf('解析视频')>=0){bs[i].click();return 'ok';}"
            + "}"
            + "return 'nobtn';"
            + "},400);"
            + "})()";
        bgWeb.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (v != null && v.contains("ok")) {
                    main.postDelayed(new Runnable() { public void run() { hellotikReadVideo(0); } }, 3000);
                } else if (round < 8) {
                    main.postDelayed(new Runnable() { public void run() { hellotikSubmit(round + 1); } }, 1500);
                } else {
                    main.post(new Runnable() { public void run() { addRecord("B站", "hellotik页面异常"); } });
                    parseDone();
                }
            }
        });
    }

    /** hellotik 流程②：轮询读取结果区 <video src="..."> 的直链 */
    private void hellotikReadVideo(final int round) {
        if (pendingBili == null || bgWeb == null) return;
        String js = "(function(){"
            + "var vs=document.querySelectorAll('video');"
            + "for(var i=0;i<vs.length;i++){"
            + "var s=vs[i].getAttribute('src')||'';"
            + "if(s.indexOf('http')==0&&s.length>30){return s;}"
            + "}"
            + "return '';"
            + "})()";
        bgWeb.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                String url = null;
                if (v != null && v.length() > 4) {
                    url = v.trim();
                    if (url.startsWith("\"") && url.endsWith("\"")) {
                        url = url.substring(1, url.length() - 1);
                    }
                    url = url.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&");
                    if (url.equals("''") || url.equals("\"\"")) url = null;
                }
                String l = url == null ? "" : url.toLowerCase();
                boolean hit = l.startsWith("http")
                    && (l.contains("bilivideo") || l.contains("upos-") || l.contains(".m4s")
                        || l.contains(".mp4") || l.contains(".m3u8") || l.contains(".flv"));
                if (hit) {
                    parseDone();
                    main.post(new Runnable() {
                        public void run() { if (bgWeb != null) bgWeb.stopLoading(); }
                    });
                    final String f = url;
                    main.post(new Runnable() { public void run() { addRecord("B站", f); } });
                } else if (round < 15 && parsing) {
                    main.postDelayed(new Runnable() { public void run() { hellotikReadVideo(round + 1); } }, 2000);
                } else {
                    main.post(new Runnable() { public void run() { addRecord("B站", "hellotik未取到视频直链"); } });
                    parseDone();
                }
            }
        });
    }

    private void injectPeanutFill() {
        injectPeanutFill(0);
    }

    private void injectPeanutFill(final int retry) {
        if (pendingBili == null || bgWeb == null) return;
        final String bili = pendingBili.replace("'", "");
        String js = "(function(){"
            + "var inp=document.querySelector('input[type=url]')"
            + "||document.querySelector('input[aria-label*=\"粘贴\"]');"
            + "if(!inp){return 'noinp';}"
            + "var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');"
            + "d.set.call(inp,'" + bili + "');"
            + "inp.dispatchEvent(new Event('input',{bubbles:true}));"
            + "setTimeout(function(){"
            + "var b=document.querySelector('button[type=submit]');"
            + "if(!b){var bs=document.getElementsByTagName('button');"
            + "for(var i=0;i<bs.length;i++){var t=bs[i].textContent||'';"
            + "if(t.indexOf('获取')>=0||t.indexOf('解析')>=0||t.indexOf('下载')>=0){b=bs[i];break;}}}"
            + "if(b){b.click();return 'ok';}"
            + "return 'nobtn';"
            + "},400);"
            + "return 'filled';"
            + "})()";
        bgWeb.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (v == null) return;
                if (v.contains("filled") || v.contains("ok")) {
                    if (retry == 0) {
                        main.postDelayed(new Runnable() { public void run() { pollPeanutResult(0); } }, 1500);
                    }
                } else if (retry < 5) {
                    main.postDelayed(new Runnable() {
                        public void run() { injectPeanutFill(retry + 1); }
                    }, 2000);
                } else {
                    main.post(new Runnable() { public void run() { addRecord("B站", "后台填表失败(页面结构变了)"); } });
                }
            }
        });
    }

    private void pollPeanutResult(final int round) {
        if (bgWeb == null || !parsing) return;
        String js = "(function(){"
            + "var r=(document.documentElement.innerHTML.match(/https?:[^\\\"']{10,600}\\.mp4[^\\\"'\\\\s]{0,80}/g)||[])"
            + ".filter(function(u){return /bilivideo|upos/.test(u)});"
            + "return JSON.stringify(r.slice(0,5));"
            + "})()";
        bgWeb.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (v == null || "null".equals(v) || "[]".equals(v)) {
                    if (round < 20 && parsing) {
                        main.postDelayed(new Runnable() { public void run() { pollPeanutResult(round + 1); } }, 1500);
                    } else if (round >= 20) {
                        main.post(new Runnable() { public void run() { addRecord("B站", "后台约40秒未取到结果"); } });
                        parseDone();
                    }
                    return;
                }
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(v);
                    final java.util.List<String> cands = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String u = arr.getString(i).replace("\\u0026", "&").replace("&amp;", "&");
                        if (seenMedia.add(u)) cands.add(u);
                    }
                    if (!cands.isEmpty()) {
                        parseDone();
                        main.post(new Runnable() {
                            public void run() { if (bgWeb != null) bgWeb.stopLoading(); }
                        });
                        // 多个候选时选体积最大的（=最高画质）自动开播
                        pickBestBili(cands);
                    } else if (round < 20 && parsing) {
                        main.postDelayed(new Runnable() { public void run() { pollPeanutResult(round + 1); } }, 1500);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 择优：Content-Length 最大者视为最高画质，加入记录并自动开播 */
    private void pickBestBili(final java.util.List<String> urls) {
        new Thread(new Runnable() {
            public void run() {
                String best = urls.get(0);
                long bestLen = -1;
                for (String u : urls) {
                    long len = remoteSize(u);
                    if (len > bestLen) { bestLen = len; best = u; }
                }
                final String f = best;
                main.post(new Runnable() { public void run() { addRecord("B站", f); } });
            }
        }).start();
    }

    private long remoteSize(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            long len = c.getContentLengthLong();
            return len < 0 ? 0 : len;
        } catch (Throwable e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 油猴脚本：下载并注入「抖音网页版全能优化」(greasyfork 584735) */
    private String userscriptCache = null;
    private boolean userscriptTried = false;
    private static final String USERSCRIPT_URL = "https://update.greasyfork.org/scripts/584735/%E6%8A%96%E9%9F%B3%E7%BD%91%E9%A1%B5%E7%89%88%E5%85%A8%E8%83%BD%E4%BC%98%E5%8C%96.user.js";
    private static final String USERSCRIPT_VUE = "https://cdnjs.cloudflare.com/ajax/libs/vue/3.2.31/vue.global.min.js";

    private void injectUserscript(final WebView view) {
        if (userscriptCache != null) { runUserscript(view); return; }
        // 离线内置：assets/douyin.user.js（greasyfork 584735 完整脚本）
        try {
            java.io.InputStream is = getAssets().open("douyin.user.js");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            is.close();
            userscriptCache = bo.toString("UTF-8");
            runUserscript(view);
        } catch (Throwable ignored) {
            // assets 失败则走在线下载兜底
            if (userscriptTried) return;
            userscriptTried = true;
            new Thread(new Runnable() { public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new java.net.URL(USERSCRIPT_URL).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", UA_MOBILE);
                    if (c.getResponseCode() != 200) { c.disconnect(); return; }
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    char[] b2 = new char[4096]; int n2;
                    while ((n2 = br.read(b2)) > 0) sb.append(b2, 0, n2);
                    br.close(); c.disconnect();
                    userscriptCache = sb.toString();
                    main.post(new Runnable() { public void run() { runUserscript(view); } });
                } catch (Throwable ignored2) {}
            }}).start();
        }
    }

    private void runUserscript(final WebView view) {
        String js = userscriptCache;
        if (js == null || js.length() < 50) return;
        // 油猴API垫片
        String shim = "if(typeof window.GM_addStyle==='undefined'){window.GM_addStyle=function(c){var s=document.createElement('style');s.textContent=c;document.head.appendChild(s);};}"
            + "if(typeof window.GM_getValue==='undefined'){window.GM_getValue=function(k,d){var v=localStorage.getItem('gm_'+k);return v===null?d:v;};window.GM_setValue=function(k,v){localStorage.setItem('gm_'+k,v);};window.GM_deleteValue=function(k){localStorage.removeItem('gm_'+k);};}"
            + "if(typeof window.GM_xmlhttpRequest==='undefined'){window.GM_xmlhttpRequest=function(d){fetch(d.url).then(function(r){return r.text();}).then(function(t){if(d.onload)d.onload({responseText:t,status:200});});};}"
            + "if(typeof window.unsafeWindow==='undefined'){window.unsafeWindow=window;}";
        view.evaluateJavascript(shim, null);
        final String escaped = js.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
        view.evaluateJavascript("(function(){try{eval('" + escaped + "');}catch(e){console.log('userscript error',e);}})();", null);
        Toast.makeText(this, "已注入油猴脚本", Toast.LENGTH_SHORT).show();
    }

    /** 抖音直播解析：移植 PlutoGuo/douyin-live-extractor（无签名，GET页面+正则+JSON） */
    private static final String DY_LIVE_COOKIE =        "enter_pc_once=1; hevc_supported=true; ttwid=1%7COnZEYGAxHABx6WRfArV8V0vfh1qUfP8AU2WYpG2ybdU%7C1754493043%7C867b28541b24aca9aec6379357aa2bff731e159fa7a804a767f575c8ff886639; __ac_nonce=06893707d00e64c4488d5; __ac_signature=_02B4Z6wo00f01m0zFcQAAIDDRDeLuhFSmo5tExFAAPPu88; odin_tt=e0bcb4ad345d3ed6915b71cab9469cb459681b743f65d870cb52329adfa8792b80633cf01c31edd9cf4874b743daacc7b789efd1727202b7ed3f6e7059ce43a72f3358995fc8367000f0b42103a78d1b; passport_csrf_token=4d713363889176dba46a4d28394acf2f";

    private void douyinLiveExtract(final String liveUrl) { douyinLiveExtract(liveUrl, null); }

    private void douyinLiveExtract(final String liveUrl, final String cookie) {
        new Thread(new Runnable() { public void run() {
            try {
                HttpURLConnection c = (HttpURLConnection) new java.net.URL(liveUrl).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36");
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
                c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                c.setRequestProperty("Cookie", cookie != null && cookie.length() > 10 ? cookie : DY_LIVE_COOKIE);
                if (c.getResponseCode() != 200) { c.disconnect(); failDouyin("页面请求失败:" + c.getResponseCode()); return; }
                java.io.InputStream ins = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] rb = new byte[8192]; int rn;
                while ((rn = ins.read(rb)) > 0) bo.write(rb, 0, rn);
                ins.close(); c.disconnect();
                String html = bo.toString("UTF-8");

                // 主模式 + 备用模式（与 extractor 的 patterns 一致）
                java.util.List<String> matches = new java.util.ArrayList<>();
                String[] patterns = {
                    "self\\.__pace_f\\.push\\(\\[1,\\s*\"(\\{.*?data.*?\\})\"\\]\\)",
                    "self\\.__pace_f\\.push\\(\\[1,\\s*\"(\\{.*?common.*?data.*?\\})\"\\]\\)",
                    "\"(\\{.*?common.*?stream_name.*?data.*?\\})\"",
                    "self\\.__pace_f\\.push\\(\\[1,\\s*\"([^\"]*\\{.*?data.*?\\}[^\"]*)\"\\]\\)",
                    "\"data\":\\s*(\\{.*?\"origin\".*?\\})"
                };
                for (String p : patterns) {
                    Matcher pm = Pattern.compile(p, Pattern.DOTALL).matcher(html);
                    while (pm.find()) matches.add(pm.group(1));
                    if (!matches.isEmpty()) break;
                }
                if (matches.isEmpty()) { failDouyin("页面里没有流数据(未开播或需更新Cookie)"); return; }

                org.json.JSONObject streamData = null;
                for (String m : matches) {
                    String jsonStr = m.replace("\\\"", "\"").replace("\\\\", "\\");
                    org.json.JSONObject pd = tryJson(jsonStr);
                    if (pd == null) {
                        String cleaned = jsonStr.replaceAll("^[^{]*", "").replaceAll("[^}]*$", "");
                        pd = tryJson(cleaned);
                    }
                    if (pd != null && pd.has("data")) { streamData = pd; break; }
                }
                if (streamData == null) { failDouyin("流数据JSON解析失败"); return; }

                org.json.JSONObject data = streamData.getJSONObject("data");
                // 原画优先，依次降级
                String[] qualities = {"origin", "uhd", "hd", "sd", "ld", "md"};
                for (String q : qualities) {
                    if (!data.has(q)) continue;
                    org.json.JSONObject qd = data.getJSONObject(q);
                    String[][] lines = {{"main"}, {"backup"}};
                    for (String[] line : lines) {
                        if (!qd.has(line[0])) continue;
                        org.json.JSONObject ld = qd.getJSONObject(line[0]);
                        String u = ld.has("hls") ? ld.optString("hls", "") : "";
                        if (u.isEmpty()) u = ld.optString("flv", "");
                        if (!u.isEmpty()) {
                            u = u.replace("\\u0026", "&").replace("\\/", "/");
                            final String fu = u;
                            final String fq = q;
                            main.post(new Runnable() { public void run() {
                                addRecord("抖音直播", fu);
                                parseDone();
                                PlayerActivity.start(MainActivity.this, fu, "抖音直播", true, false);
                            }});
                            return;
                        }
                    }
                }
                failDouyin("各清晰度都没取到流地址");
            } catch (Throwable e) {
                failDouyin("解析异常:" + e.getMessage());
            }
        }}).start();
    }

    private org.json.JSONObject tryJson(String s) {
        try { return new org.json.JSONObject(s); } catch (Throwable e) { return null; }
    }

    private void failDouyin(final String msg) {
        main.post(new Runnable() { public void run() {
            addRecord("抖音直播", msg);
            parseDone();
        }});
    }

    /** 抖音直播看门狗：8秒还没抓到裸地址就刷新浏览页，直到抓到为止 */
    private final Runnable douyinWatchdog = new Runnable() {
        public void run() {
            if (!parsing || bgWeb == null) return;
            boolean hasBare;
            synchronized (douyinCands) { hasBare = !douyinCands.isEmpty(); }
            if (!hasBare) {
                bgWeb.post(new Runnable() { public void run() {
                    try { bgWeb.reload(); } catch (Throwable ignored) {}
                }});
            }
            if (hasBare && douyinPickScheduled) return;  // 已进入择优流程，停止看门狗
            main.postDelayed(this, 8000);
        }
    };

    private void startDouyinWatchdog() {
        main.removeCallbacks(douyinWatchdog);
        main.postDelayed(douyinWatchdog, 8000);
    }

    /** 抖音候选收集后按体积择优，自动开播最大者 */
    private void finishDouyinPick() {
        java.util.List<String> snapshot;
        synchronized (douyinCands) { snapshot = new java.util.ArrayList<>(douyinCands); }
        if (snapshot.isEmpty()) {
            if (parsing) main.postDelayed(new Runnable() { public void run() { finishDouyinPick(); } }, 2000);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String best = snapshot.get(0);
                long bestLen = -1;
                for (String u : snapshot) {
                    long len = remoteSizeDouyin(u);
                    if (len > bestLen) { bestLen = len; best = u; }
                }
                final String f = best;
                main.post(new Runnable() { public void run() {
                    addRecord("抖音", f);
                    parseDone();
                }});
            }
        }).start();
    }

    private long remoteSizeDouyin(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", UA_MOBILE);
            c.setRequestProperty("Referer", "https://live.douyin.com/");
            long len = c.getContentLengthLong();
            return len < 0 ? 0 : len;
        } catch (Throwable e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void maybeRecordBiliMedia(String url) {
        if (url == null) return;
        String l = url.toLowerCase();
        if (l.contains("/log/") || l.contains("data.bilibili.com")) return;
        boolean hit = l.contains(".mp4") || l.contains(".m4s")
            || l.contains(".flv") || l.contains(".m3u8")
            || l.contains("bilivideo") || l.contains("upos-");
        if (!hit || !seenMedia.add(url)) return;
        if (bgWeb != null && !bgDone) {
            bgDone = true;
            main.post(new Runnable() {
                public void run() {
                    if (bgWeb != null) bgWeb.stopLoading();
                    parseDone();
                }
            });
        }
        final String f = url;
        main.post(new Runnable() { public void run() { addRecord("B站", f); } });
    }

    private void addRecord(String label, final String rawUrl) {
        recordId++;
        final int id = recordId;
        final boolean isBili = rawUrl.contains("bilibili.com") || rawUrl.contains("bilivideo");
        final boolean isDy = rawUrl.contains("douyin");
        final String streamUrl;
        // 默认直连播放（Referer 头由播放页注入），代理改由「代理」按键触发
        streamUrl = rawUrl;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (list.getChildCount() > 0) lp.topMargin = 2;
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(label + " · " + id);
        tv.setTextColor("B站".equals(label) ? 0xFF4FA8FF : 0xFF2ED573);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_btn);
        tv.setPadding(24, 12, 24, 12);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { handToPlayer(streamUrl); }
        });
        row.addView(tv);

        TextView tvUrl2 = new TextView(this);
        tvUrl2.setText(rawUrl);
        tvUrl2.setTextColor(0xFF8A94A6);
        tvUrl2.setTextSize(10);
        tvUrl2.setPadding(8, 0, 8, 0);
        tvUrl2.setMaxLines(2);
        tvUrl2.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp2.leftMargin = 8;
        tvUrl2.setLayoutParams(lp2);
        tvUrl2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("流地址", streamUrl));
                Toast.makeText(MainActivity.this, "已复制链接", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(tvUrl2);

        TextView btn = new TextView(this);
        btn.setText("播放");
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.bg_btn);
        btn.setPadding(28, 12, 28, 12);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { handToPlayer(streamUrl); }
        });
        row.addView(btn);

        list.addView(row, 0);

        if (rawUrl.startsWith("http") && (isBili || isDy || isStreamUrl(rawUrl))) {
            autoCandidates.add(streamUrl);
            if (!autoPlayed) {
                autoPlayed = true;
                new Thread(new Runnable() {
                    public void run() {
                        try { Thread.sleep(4000); } catch (Exception ignored) {}
                        main.post(new Runnable() {
                            public void run() {
                                String best = null; long bestBr = -1;
                                for (String u : autoCandidates) {
                                    long br = 0;
                                    Matcher vm = Pattern.compile("biz_vbitrate=(\\d+)").matcher(u);
                                    if (vm.find()) br = Long.parseLong(vm.group(1));
                                    Matcher rm = Pattern.compile("ratio=1080p").matcher(u);
                                    if (rm.find()) br += 10000000;
                                    if (br > bestBr) { bestBr = br; best = u; }
                                }
                                if (best == null && !autoCandidates.isEmpty()) best = autoCandidates.get(0);
                                if (best != null) handToPlayer(best);
                            }
                        });
                    }
                }).start();
            }
        }
    }

    private void handToPlayer(String streamUrl) {
        if (bgWeb != null) {
            bgWeb.evaluateJavascript(
                "(function(){var m=document.querySelectorAll('video');for(var i=0;i<m.length;i++){try{m[i].pause();m[i].removeAttribute('src');m[i].load();}catch(e){}}})()", null);
        }
        PlayerActivity.start(this, streamUrl, "播放", false, false);
    }

    @Override
    protected void onDestroy() {
        if (bgWeb != null) {
            try { bgWeb.destroy(); } catch (Throwable ignored) {}
            bgWeb = null;
        }
        super.onDestroy();
    }
}
