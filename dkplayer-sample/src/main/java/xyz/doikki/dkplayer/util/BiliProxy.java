package xyz.doikki.dkplayer.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 本地中转代理（自小工具 LiveProxy 精简）：仅保留 /bili 与 /dy 两个中转，
 * 带对应站点 Referer/UA 转发媒体请求，支持 Range，供播放器绕过防盗链。
 */
public final class BiliProxy {

    public static final int PORT = 8123;
    private static volatile boolean running = false;

    public static void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    ServerSocket ss = new ServerSocket(PORT);
                    while (running) {
                        final Socket s = ss.accept();
                        new Thread(new Runnable() {
                            public void run() { handle(s); }
                        }).start();
                    }
                } catch (Throwable ignored) {
                    running = false;
                }
            }
        }).start();
    }

    private static void handle(Socket s) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = br.readLine();
            if (line == null) { s.close(); return; }
            String path = line.split(" ")[1];
            String range = null;
            String hl;
            while ((hl = br.readLine()) != null && !hl.isEmpty()) {
                if (hl.toLowerCase().startsWith("range:")) range = hl.substring(6).trim();
            }
            String raw;
            String referer;
            String ua;
            if (path.startsWith("/bili")) {
                raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                referer = "https://www.bilibili.com/";
                ua = "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile";
            } else if (path.startsWith("/dy")) {
                raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                referer = "https://www.douyin.com/";
                ua = "Mozilla/5.0 (Linux; Android 10; K) Chrome/120 Mobile";
            } else {
                writeResp(s, "404 Not Found", "text/plain", "no route".getBytes());
                return;
            }
            relay(s, raw, referer, ua, range);
        } catch (Throwable ignored) {
            try { s.close(); } catch (Throwable ignored2) {}
        }
    }

    private static void relay(Socket s, String raw, String referer, String ua, String range) throws Exception {
        OutputStream os = s.getOutputStream();
        try {
            HttpURLConnection oc = (HttpURLConnection) new URL(raw).openConnection();
            oc.setConnectTimeout(8000);
            oc.setReadTimeout(30000);
            oc.setRequestProperty("User-Agent", ua);
            oc.setRequestProperty("Referer", referer);
            if (range != null) oc.setRequestProperty("Range", range);
            int code = oc.getResponseCode();
            String ct = oc.getContentType();
            String cr = oc.getHeaderField("Content-Range");
            String cl = oc.getHeaderField("Content-Length");
            StringBuilder hh = new StringBuilder();
            hh.append("HTTP/1.1 ").append(code).append(" OK\r\n");
            hh.append("Content-Type: ").append(ct != null ? ct : "video/mp4").append("\r\n");
            if (cr != null) hh.append("Content-Range: ").append(cr).append("\r\n");
            if (cl != null) hh.append("Content-Length: ").append(cl).append("\r\n");
            hh.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            os.write(hh.toString().getBytes());
            InputStream in = oc.getInputStream();
            byte[] rb = new byte[65536];
            int rn;
            while ((rn = in.read(rb)) > 0) os.write(rb, 0, rn);
            os.flush();
            in.close();
            oc.disconnect();
        } catch (Throwable e) {
            try { writeResp(s, "502 Bad Gateway", "text/plain", "upstream err".getBytes()); } catch (Throwable ignored) {}
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static String queryParam(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String p : path.substring(q + 1).split("&")) {
            int e = p.indexOf('=');
            if (e > 0 && key.equals(p.substring(0, e))) return p.substring(e + 1);
        }
        return "";
    }

    private static void writeResp(Socket s, String status, String type, byte[] body) throws Exception {
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type
            + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes());
        os.write(body);
        os.flush();
        s.close();
    }
}
