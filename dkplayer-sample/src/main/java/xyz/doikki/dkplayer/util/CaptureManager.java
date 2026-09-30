package xyz.doikki.dkplayer.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.Map;

import xyz.doikki.dkplayer.util.cache.ProxyVideoCacheManager;

/**
 * 全局实时抓流管理器：播放器里点「缓存抓流」启动，退到后台/离开播放器继续写盘，
 * 下载页可查看状态并 暂停/继续/停止
 */
public final class CaptureManager {

    @Volatile public static boolean running = false;
    @Volatile public static boolean paused = false;

    public static String url = null;
    public static Map<String, String> hdrs = null;
    public static String ext = "mp4";
    public static File outFile = null;

    private static Thread thread;

    private CaptureManager() {}

    /** 状态文本，供下载页显示 */
    public static String statusText() {
        if (!running) return "未在抓取";
        long size = outFile != null ? outFile.length() : 0;
        String st = paused ? "已暂停" : "抓取中";
        return st + " · " + (outFile != null ? outFile.getName() : "?")
                + " · " + fmt(size);
    }

    private static String fmt(long b) {
        if (b < 1024) return b + "B";
        if (b < 1048576) return String.format("%.1fKB", b / 1024f);
        if (b < 1073741824L) return String.format("%.1fMB", b / 1048576f);
        return String.format("%.2fGB", b / 1073741824f);
    }

    /** 启动新抓取（在播放器里调用） */
    public static synchronized boolean start(Context ctx, String u, Map<String, String> h, String e) {
        if (running) return false;
        url = u; hdrs = h; ext = e;
        File dir = ProxyVideoCacheManager.getCacheDir(ctx.getApplicationContext());
        outFile = new File(dir, "download_" + System.currentTimeMillis() + "." + ext);
        running = true; paused = false;
        final String fu = u; final Map<String, String> fh = h; final File fo = outFile;
        final String fe = ext;
        thread = new Thread(() -> {
            try {
                if (fe.equals("ts")) captureM3u8(fu, fh, fo); else captureDirect(fu, fh, fo);
            } catch (Throwable ignored) {
            } finally {
                running = false; paused = false;
            }
        });
        thread.start();
        return true;
    }

    public static void pause() { if (running) paused = true; }

    public static void resume() { if (running) paused = false; }

    public static synchronized void stop() {
        running = false; paused = false;
        if (thread != null) thread.interrupt();
    }

    /** 空闲等待（暂停时轮询） */
    private static void idleWait() throws InterruptedException {
        while (paused && running) Thread.sleep(500);
    }

    /** 单文件流：直接边播边写盘 */
    private static void captureDirect(String u, Map<String, String> h, File out) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
        c.connectTimeout = 8000; c.readTimeout = 15000;
        if (h != null) for (Map.Entry<String, String> en : h.entrySet()) c.setRequestProperty(en.getKey(), en.getValue());
        java.io.InputStream ins = c.getInputStream();
        java.io.FileOutputStream os = new java.io.FileOutputStream(out);
        byte[] buf = new byte[64 * 1024];
        while (running) {
            idleWait();
            int n = ins.read(buf);
            if (n <= 0) break;
            os.write(buf, 0, n);
        }
        os.close(); ins.close(); c.disconnect();
    }

    /** m3u8：逐片下载拼接成 ts；直播清单循环刷新拿新分片 */
    private static void captureM3u8(String masterUrl, Map<String, String> h, File out) throws Exception {
        java.io.FileOutputStream os = new java.io.FileOutputStream(out);
        byte[] buf = new byte[64 * 1024];
        java.util.HashSet<String> done = new java.util.HashSet<>();
        String mediaUrl = null;
        int rounds = 0;
        while (running) {
            idleWait();
            String listUrl = mediaUrl != null ? mediaUrl : masterUrl;
            String body = httpGet(listUrl, h);
            if (body == null) break;
            if (mediaUrl == null && !body.contains("#EXTINF")) {
                // 主清单：选 BANDWIDTH 最大的变体
                long bestBw = -1; String best = null; long cur = -1;
                java.net.URI base = java.net.URI.create(masterUrl);
                for (String ln : body.split("\n")) {
                    String t = ln.trim();
                    if (t.startsWith("#EXT-X-STREAM-INF")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(t);
                        cur = m.find() ? Long.parseLong(m.group(1)) : -1;
                    } else if (!t.isEmpty() && !t.startsWith("#") && cur > bestBw) {
                        bestBw = cur; best = base.resolve(t).toString();
                    }
                }
                mediaUrl = best;
                if (mediaUrl == null) break;
                continue;
            }
            // 媒体清单：顺序抓没下过的分片
            java.net.URI base = java.net.URI.create(listUrl);
            boolean gotNew = false;
            for (String ln : body.split("\n")) {
                String t = ln.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String seg = base.resolve(t).toString();
                if (done.contains(seg)) continue;
                done.add(seg);
                gotNew = true;
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(seg).openConnection();
                c.connectTimeout = 8000; c.readTimeout = 15000;
                if (h != null) for (Map.Entry<String, String> en : h.entrySet()) c.setRequestProperty(en.getKey(), en.getValue());
                java.io.InputStream ins = c.getInputStream();
                while (running) {
                    idleWait();
                    int n = ins.read(buf);
                    if (n <= 0) break;
                    os.write(buf, 0, n);
                }
                ins.close(); c.disconnect();
                if (!running) break;
            }
            boolean isLive = body.contains("#EXT-X-MEDIA-SEQUENCE") && !body.contains("#EXT-X-ENDLIST");
            if (!isLive) break;                 // 点播抓完即止
            if (rounds++ > 7200) break;         // 直播最多约2小时
            Thread.sleep(2000);
        }
        os.close();
        running = false; paused = false;
    }

    private static String httpGet(String u, Map<String, String> h) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.connectTimeout = 8000; c.readTimeout = 8000;
            if (h != null) for (Map.Entry<String, String> en : h.entrySet()) c.setRequestProperty(en.getKey(), en.getValue());
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            java.io.InputStream ins = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = ins.read(b)) > 0) bo.write(b, 0, n);
            ins.close(); c.disconnect();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
