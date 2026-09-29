package xyz.doikki.dkplayer.util;

import android.content.Context;
import android.content.Intent;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Enumeration;
import java.util.UUID;

import xyz.doikki.dkplayer.activity.api.PlayerActivity;

/**
 * 极简 DLNA/UPnP MediaRenderer：接收其他App（B站/抖音投屏按钮等）的推流。
 * SSDP 应答 + AVTransport SetAVTransportURI 接收播放地址 → 交给播放器。
 */
public final class DlnaRenderer {

    private static final int HTTP_PORT = 49152;
    private static final String UUID_STR = "uuid:" + UUID.randomUUID().toString();
    private static volatile boolean running = false;
    private static OnPlay listener;

    public interface OnPlay { void onPlay(String url, String title); }

    public static void setOnPlay(OnPlay l) { listener = l; }

    public static void start(Context ctx) {
        if (running) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        setOnPlay(new OnPlay() {
            public void onPlay(String url, String title) {
                Intent it = new Intent(app, PlayerActivity.class);
                it.putExtra("url", url);
                it.putExtra("title", title == null ? "投屏" : title);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(it);
            }
        });
        new Thread(new Runnable() { public void run() { httpLoop(app); } }, "dlna-http").start();
        new Thread(new Runnable() { public void run() { ssdpLoop(); } }, "dlna-ssdp").start();
    }

    private static String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "0.0.0.0";
    }

    // ---------- SSDP ----------
    private static void ssdpLoop() {
        try {
            DatagramSocket ss = new DatagramSocket(1900);
            ss.setReuseAddress(true);
            byte[] buf = new byte[2048];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ss.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength());
                    if (!msg.contains("M-SEARCH")) continue;
                    String st = null;
                    for (String line : msg.split("\r\n")) {
                        if (line.toUpperCase().startsWith("ST:")) st = line.substring(3).trim();
                    }
                    if (st == null) continue;
                    boolean hit = st.equals("ssdp:all") || st.equals("upnp:rootdevice")
                        || st.contains("MediaRenderer") || st.contains("AVTransport");
                    if (!hit) continue;
                    respond(ss, p.getAddress(), p.getPort(), st);
                    Thread.sleep(120);
                    respond(ss, p.getAddress(), p.getPort(), st); // 双回应答防丢
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void respond(DatagramSocket ss, InetAddress to, int port, String st) {
        try {
            String ip = localIp();
            String r = "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "EXT:\r\n"
                + "LOCATION: http://" + ip + ":" + HTTP_PORT + "/description.xml\r\n"
                + "SERVER: Android UPnP/1.0 DKPlayer/1.0\r\n"
                + "ST: " + st + "\r\n"
                + "USN: " + UUID_STR + "::" + st + "\r\n\r\n";
            ss.send(new DatagramPacket(r.getBytes(), r.length(), to, port));
        } catch (Throwable ignored) {}
    }

    // ---------- HTTP / SOAP ----------
    private static void httpLoop(final Context app) {
        try {
            ServerSocket ss = new ServerSocket(HTTP_PORT, 50);
            while (running) {
                final Socket s = ss.accept();
                new Thread(new Runnable() { public void run() { handle(app, s); } }).start();
            }
        } catch (Throwable ignored) {}
    }

    private static void handle(Context app, Socket s) {
        try {
            s.setSoTimeout(5000);
            byte[] buf = new byte[16384];
            java.io.InputStream in = s.getInputStream();
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                total += n;
                String head = new String(buf, 0, total);
                int end = head.indexOf("\r\n\r\n");
                if (end > 0) {
                    int cl = 0;
                    for (String line : head.substring(0, end).split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:"))
                            cl = Integer.parseInt(line.substring(15).trim());
                    }
                    if (total >= end + 4 + cl) break;
                }
            }
            String req = new String(buf, 0, total);
            String path = req.split(" ")[1];
            String body;
            if (path.startsWith("/description.xml")) {
                body = description();
            } else if (path.startsWith("/control/")) {
                String action = "unknown";
                java.util.regex.Matcher am = java.util.regex.Pattern
                    .compile("\"urn:schemas-upnp-org:service:[^\"]+#(\\w+)\"").matcher(req);
                if (am.find()) action = am.group(1);
                if (action.equals("SetAVTransportURI")) {
                    java.util.regex.Matcher um = java.util.regex.Pattern
                        .compile("<CurrentURI[^>]*>([^<]+)</CurrentURI>").matcher(req);
                    java.util.regex.Matcher tm = java.util.regex.Pattern
                        .compile("<CurrentURIMetaData[^>]*>([^<]*)</CurrentURIMetaData>").matcher(req);
                    if (um.find()) {
                        String url = java.net.URLDecoder.decode(um.group(1)
                            .replace("&amp;", "&"), "UTF-8");
                        String title = "投屏";
                        if (tm.find()) {
                            java.util.regex.Matcher dm = java.util.regex.Pattern
                                .compile("dc:title>([^<]+)<").matcher(tm.group(1));
                            if (dm.find()) title = dm.group(1);
                        }
                        final String f = url, t = title;
                        if (listener != null) listener.onPlay(f, t);
                    }
                }
                body = soapResp(action);
            } else {
                body = "";
            }
            byte[] out = body.getBytes("UTF-8");
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + out.length + "\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(head.getBytes());
            s.getOutputStream().write(out);
            s.getOutputStream().flush();
            s.close();
        } catch (Throwable ignored) {
            try { s.close(); } catch (Throwable ignored2) {}
        }
    }

    private static String soapResp(String action) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
            + "<s:Body><u:" + action + "Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
            + "</u:" + action + "Response></s:Body></s:Envelope>";
    }

    private static String description() {
        String ip = localIp();
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion>"
            + "<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
            + "<friendlyName>DKPlayer(" + ip + ")</friendlyName>"
            + "<manufacturer>dkplayer</manufacturer>"
            + "<modelName>DKPlayer</modelName>"
            + "<UDN>" + UUID_STR + "</UDN>"
            + "<serviceList><service>"
            + "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
            + "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>"
            + "<SCPDURL>/scpd.xml</SCPDURL>"
            + "<controlURL>/control/AVTransport</controlURL>"
            + "<eventSubURL>/event</eventSubURL>"
            + "</service></serviceList></device></root>";
    }
}
