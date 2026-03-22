package com.cutter.plotterctl;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.*;

public class WebServer {

    private final int port;
    private final PlotterProtocol protocol;
    private final CutJob job;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private Listener listener;

    public interface Listener {
        void onLog(String msg);
    }

    public WebServer(int port, PlotterProtocol protocol, CutJob job) {
        this.port = port;
        this.protocol = protocol;
        this.job = job;
    }

    public void setListener(Listener l) { this.listener = l; }

    private void log(String msg) {
        // Log to both the UI listener and Android logcat
        try { android.util.Log.d("OpenBladeRunner", msg); } catch (Exception e) {}
        if (listener != null) listener.onLog(msg);
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
                log("Web server listening on port " + port);
                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        new Thread(() -> handleConnection(socket)).start();
                    } catch (Exception e) {
                        if (running) log("Accept error: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                log("Server error: " + e.getMessage());
                // Retry on 8080 if 80 fails
                if (port == 80) {
                    log("Retrying on port 8080...");
                    try {
                        serverSocket = new ServerSocket(8080, 50, InetAddress.getByName("0.0.0.0"));
                        log("Web server listening on port 8080");
                        while (running) {
                            try {
                                Socket socket = serverSocket.accept();
                                new Thread(() -> handleConnection(socket)).start();
                            } catch (Exception e2) {
                                if (running) log("Accept error: " + e2.getMessage());
                            }
                        }
                    } catch (Exception e2) {
                        log("Failed on 8080 too: " + e2.getMessage());
                    }
                }
            }
        }, "WebServer").start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read request line
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { socket.close(); return; }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { socket.close(); return; }
            String method = parts[0];
            String path = parts[1];

            // Read headers
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
                }
            }

            // Read body if content-length present
            byte[] body = null;
            String clStr = headers.get("content-length");
            if (clStr != null) {
                int cl = Integer.parseInt(clStr.trim());
                body = new byte[cl];
                int read = 0;
                while (read < cl) {
                    int n = in.read(body, read, cl - read);
                    if (n == -1) break;
                    read += n;
                }
            }

            // Route
            if ("GET".equals(method) && "/".equals(path)) {
                sendHtml(out, 200, WebUI.MAIN_HTML);
            } else if ("GET".equals(method) && "/api/status".equals(path)) {
                sendJson(out, 200, job.toJson());
            } else if ("GET".equals(method) && "/api/preview".equals(path)) {
                handlePreview(out);
            } else if ("POST".equals(method) && "/api/upload".equals(path)) {
                handleUpload(out, headers, body);
            } else if ("POST".equals(method) && "/api/cut".equals(path)) {
                handleCut(out, body);
            } else if ("POST".equals(method) && "/api/stop".equals(path)) {
                handleStop(out);
            } else {
                sendJson(out, 404, "{\"error\":\"not found\"}");
            }

            socket.close();
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void handlePreview(OutputStream out) throws Exception {
        String hpgl = job.getHpglData();
        if (hpgl == null || hpgl.isEmpty()) {
            sendJson(out, 200, "{\"points\":[],\"minX\":0,\"minY\":0,\"maxX\":0,\"maxY\":0,\"speed\":0,\"force\":0}");
            return;
        }
        HpglParser.ParseResult r = HpglParser.parse(hpgl);
        sendJson(out, 200, HpglParser.previewJson(r));
    }

    private void handleUpload(OutputStream out, Map<String, String> headers, byte[] body) throws Exception {
        if (body == null || body.length == 0) {
            sendJson(out, 400, "{\"ok\":false,\"error\":\"No data\"}");
            return;
        }

        String contentType = headers.get("content-type");
        String hpglData = null;
        String fileName = "upload.hpgl";

        if (contentType != null && contentType.contains("multipart/form-data")) {
            // Extract boundary
            int bIdx = contentType.indexOf("boundary=");
            if (bIdx < 0) {
                sendJson(out, 400, "{\"ok\":false,\"error\":\"No boundary\"}");
                return;
            }
            String boundary = contentType.substring(bIdx + 9).trim();
            // Remove quotes if present
            if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }

            String bodyStr = new String(body, "UTF-8");
            String sep = "--" + boundary;
            String[] partsList = bodyStr.split(sep);

            for (String part : partsList) {
                if (part.contains("Content-Disposition") && part.contains("filename=")) {
                    // Extract filename
                    int fnIdx = part.indexOf("filename=\"");
                    if (fnIdx >= 0) {
                        int fnEnd = part.indexOf("\"", fnIdx + 10);
                        if (fnEnd > fnIdx) {
                            fileName = part.substring(fnIdx + 10, fnEnd);
                        }
                    }
                    // Extract file content (after double newline)
                    int contentStart = part.indexOf("\r\n\r\n");
                    if (contentStart < 0) contentStart = part.indexOf("\n\n");
                    if (contentStart >= 0) {
                        String content = part.substring(contentStart + (part.charAt(contentStart) == '\r' ? 4 : 2));
                        // Remove trailing \r\n before next boundary
                        if (content.endsWith("\r\n")) content = content.substring(0, content.length() - 2);
                        else if (content.endsWith("\n")) content = content.substring(0, content.length() - 1);
                        hpglData = content;
                    }
                }
            }
        } else {
            // Raw body
            hpglData = new String(body, "UTF-8");
        }

        if (hpglData == null || hpglData.trim().isEmpty()) {
            sendJson(out, 400, "{\"ok\":false,\"error\":\"Empty file\"}");
            return;
        }

        job.setHpglData(hpglData);
        job.setFileName(fileName);
        job.setState(CutJob.State.READY);

        HpglParser.ParseResult r = HpglParser.parse(hpglData);
        log("Uploaded: " + fileName + " (" + r.points.size() + " points)");

        sendJson(out, 200, "{\"ok\":true,\"fileName\":\"" + esc(fileName) + "\""
            + ",\"points\":" + r.points.size()
            + ",\"minX\":" + r.minX + ",\"minY\":" + r.minY
            + ",\"maxX\":" + r.maxX + ",\"maxY\":" + r.maxY
            + ",\"speed\":" + r.existingSpeed + ",\"force\":" + r.existingForce + "}");
    }

    private void handleCut(OutputStream out, byte[] body) throws Exception {
        if (job.getState() == CutJob.State.CUTTING) {
            sendJson(out, 409, "{\"ok\":false,\"error\":\"Already cutting\"}");
            return;
        }
        String hpgl = job.getHpglData();
        if (hpgl == null || hpgl.isEmpty()) {
            sendJson(out, 400, "{\"ok\":false,\"error\":\"No file uploaded\"}");
            return;
        }

        // Parse speed/force/feed/offset from body
        int speed = job.getSpeed();
        int force = job.getForce();
        int feed = 200; // default 200mm paper feed
        int offsetX = 0; // mm
        int offsetY = 0; // mm
        if (body != null && body.length > 0) {
            String json = new String(body, "UTF-8");
            speed = jsonInt(json, "speed", speed);
            force = jsonInt(json, "force", force);
            feed = jsonInt(json, "feed", feed);
            offsetX = jsonInt(json, "offsetX", offsetX);
            offsetY = jsonInt(json, "offsetY", offsetY);
        }
        job.setSpeed(speed);
        job.setForce(force);

        // Rewrite HPGL with user parameters
        String rewritten = HpglParser.rewrite(hpgl, speed, force);

        // Swap X/Y axes: HPGL files use X=horizontal Y=vertical,
        // but this plotter uses X=roller(feed) Y=head(horizontal).
        rewritten = HpglParser.swapAxes(rewritten);

        // After swap: HPGL X = roller (paper feed), HPGL Y = head (horizontal)
        // Feed adds to X (roller direction), offsetX shifts Y (head), offsetY shifts X (roller)
        int totalDx = (feed + offsetY) * 40;  // roller: feed + vertical offset
        int totalDy = offsetX * 40;            // head: horizontal offset
        if (totalDx != 0 || totalDy != 0) {
            rewritten = HpglParser.applyOffset(rewritten, totalDx, totalDy);
        }

        log("Cut HPGL (first 200 chars): " + rewritten.substring(0, Math.min(200, rewritten.length())));
        log("Feed=" + feed + "mm offX=" + offsetX + "mm offY=" + offsetY + "mm → dx=" + totalDx + " dy=" + totalDy);

        sendJson(out, 200, "{\"ok\":true}");

        final String cutData = rewritten;
        new Thread(() -> executeCut(cutData), "CutThread").start();
    }

    /** Public stop method - callable from Android UI and web API.
     *  Does NOT use synchronized - writes directly to the serial stream
     *  to bypass the cutting thread which may be holding the lock. */
    public void stopCut() {
        // Set state FIRST so cutting thread breaks out of its loop
        job.setState(CutJob.State.IDLE);
        log("EMERGENCY STOP triggered");

        if (protocol == null) {
            log("No protocol - can't send stop");
            return;
        }
        try {
            // Send stop command CC 0040 directly (no lock)
            byte[] stopPkt = PlotterProtocol.buildCutPacket(0x40, null);
            log("Sending CC0040 stop: " + PlotterProtocol.bytesToHex(stopPkt));
            protocol.sendDirect(stopPkt);

            // Also send reset knife BB 001B as fallback
            byte[] resetPkt = PlotterProtocol.buildSet(0x1B, null);
            log("Sending BB001B reset: " + PlotterProtocol.bytesToHex(resetPkt));
            protocol.sendDirect(resetPkt);

            log("Stop commands sent");
        } catch (Exception e) {
            log("Stop error: " + e.getMessage());
        }
    }

    private void handleStop(OutputStream out) throws Exception {
        stopCut();
        sendJson(out, 200, "{\"ok\":true}");
    }

    private void executeCut(String hpglData) {
        job.setState(CutJob.State.FEEDING);
        job.setSentChunks(0);

        try {
            if (protocol == null) {
                throw new Exception("Plotter not connected");
            }

            // First, expand the plotter's working area to fit the design + offset.
            // Command BB 0012 sets working area as (X_LE16, Y_LE16) in HPGL units.
            // Parse max coordinates from the final HPGL to determine required area.
            HpglParser.ParseResult bounds = HpglParser.parse(hpglData);
            int needX = Math.max(bounds.maxX + 400, 12000); // roller range + margin
            int needY = Math.max(bounds.maxY + 400, 12000); // head range + margin
            log("Setting working area to " + needX + "x" + needY + " units (" + (needX/40) + "x" + (needY/40) + "mm)");
            byte[] areaData = new byte[]{
                (byte)(needX & 0xFF), (byte)((needX >> 8) & 0xFF),
                (byte)(needY & 0xFF), (byte)((needY >> 8) & 0xFF)
            };
            synchronized (protocol) {
                byte[] areaPkt = PlotterProtocol.buildSet(0x12, areaData);
                protocol.send(areaPkt);
                byte[] areaResp = protocol.readResponse(2000);
                log("Working area set: " + PlotterProtocol.bytesToHex(areaResp));
            }
            Thread.sleep(200);

            log("Preparing cut data...");
            byte[] payload = hpglData.getBytes("US-ASCII");
            String payloadHex = PlotterProtocol.bytesToHex(payload);

            // Split into chunks of 1024 hex chars (512 bytes)
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < payloadHex.length(); i += 1024) {
                chunks.add(payloadHex.substring(i, Math.min(i + 1024, payloadHex.length())));
            }
            job.setTotalChunks(chunks.size() + 1); // +1 for header

            // Build header packet
            int dataLen = payload.length;
            int[] lenLE = { dataLen & 0xFF, (dataLen >> 8) & 0xFF, (dataLen >> 16) & 0xFF, (dataLen >> 24) & 0xFF };

            int[] headerCmdInts = new int[5 + 4 + 16];
            headerCmdInts[0] = 0xCC;
            headerCmdInts[1] = 0x01;
            headerCmdInts[2] = 0x30;
            headerCmdInts[3] = 0x14;
            headerCmdInts[4] = 0x00;
            headerCmdInts[5] = lenLE[0];
            headerCmdInts[6] = lenLE[1];
            headerCmdInts[7] = lenLE[2];
            headerCmdInts[8] = lenLE[3];
            byte[] testBytes = "test".getBytes("US-ASCII");
            for (int i = 0; i < 4; i++) headerCmdInts[9 + i] = testBytes[i] & 0xFF;

            byte[] headerChk = PlotterProtocol.computeChecksum(headerCmdInts);

            StringBuilder headerHex = new StringBuilder("5AA5CC01301400");
            for (int i = 5; i < headerCmdInts.length; i++) {
                headerHex.append(String.format("%02X", headerCmdInts[i]));
            }
            headerHex.append(String.format("%02X%02X", headerChk[0] & 0xFF, headerChk[1] & 0xFF));
            headerHex.append("0D0A");

            byte[] headerPacket = hexToBytes(headerHex.toString());

            synchronized (protocol) {
                log("Sending header (" + payload.length + " bytes)...");
                protocol.send(headerPacket);
                byte[] resp = protocol.readResponse(5000);
                if (resp == null) {
                    throw new Exception("No response to header packet");
                }
                log("Header accepted");
            }
            job.setSentChunks(1);
            job.setState(CutJob.State.CUTTING);
            Thread.sleep(200);

            // Send data chunks
            for (int i = 0; i < chunks.size(); i++) {
                if (job.getState() != CutJob.State.CUTTING) {
                    log("Cut cancelled at chunk " + i);
                    break;
                }

                String chunk = chunks.get(i);
                int chunkByteLen = chunk.length() / 2;

                int seq = (i == chunks.size() - 1) ? 0x00 : (i + 2);
                if (seq > 255) seq = seq - 254;

                String seqHex = String.format("%02X", seq);
                String lenLoHex = String.format("%02X", chunkByteLen & 0xFF);
                String lenHiHex = String.format("%02X", (chunkByteLen >> 8) & 0xFF);

                StringBuilder pktHex = new StringBuilder("5AA5CC");
                pktHex.append(seqHex).append("30");
                pktHex.append(lenLoHex).append(lenHiHex);
                pktHex.append(chunk);

                String chkInput = "CC" + seqHex + "30" + lenLoHex + lenHiHex + chunk;
                int sum = 0;
                byte[] chkBytes = hexToBytes(chkInput);
                for (byte b : chkBytes) sum += (b & 0xFF);
                pktHex.append(String.format("%02X", sum & 0xFF));
                pktHex.append("0D0A");

                byte[] dataPacket = hexToBytes(pktHex.toString());

                synchronized (protocol) {
                    protocol.send(dataPacket);
                    byte[] resp = protocol.readResponse(10000);
                    if (resp == null) {
                        throw new Exception("No response for chunk " + i);
                    }
                }

                job.setSentChunks(i + 2);
                log("Chunk " + (i + 1) + "/" + chunks.size() + " sent");
                Thread.sleep(50);
            }

            if (job.getState() == CutJob.State.CUTTING) {
                // Data sent but plotter still physically cutting
                job.setState(CutJob.State.SENT);
                log("All data sent. Plotter is cutting...");

                // Poll plotter position (cmd 0x25) to detect when cutting finishes
                // Position returns all zeros when idle/home
                for (int wait = 0; wait < 120; wait++) { // max 2 min
                    if (job.getState() != CutJob.State.SENT) break;
                    Thread.sleep(1000);
                }
                if (job.getState() == CutJob.State.SENT) {
                    job.setState(CutJob.State.COMPLETE);
                    log("Cut complete!");
                }
            }

        } catch (Exception e) {
            job.setState(CutJob.State.ERROR);
            job.setErrorMessage(e.getMessage());
            log("Cut error: " + e.getMessage());
        }
    }

    // --- HTTP helpers ---

    private void sendHtml(OutputStream out, int code, String html) throws Exception {
        byte[] body = html.getBytes("UTF-8");
        writeResponse(out, code, "text/html; charset=utf-8", body);
    }

    private void sendJson(OutputStream out, int code, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        writeResponse(out, code, "application/json", body);
    }

    private void writeResponse(OutputStream out, int code, String contentType, byte[] body) throws Exception {
        String status = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 404 ? "Not Found" : code == 409 ? "Conflict" : "Error";
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(status).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    private String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] result = new byte[len];
        for (int i = 0; i < len; i++) {
            int idx = i * 2;
            result[i] = (byte) Integer.parseInt(hex.substring(idx, idx + 2), 16);
        }
        return result;
    }

    private int jsonInt(String json, String key, int defVal) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return defVal;
        idx += search.length();
        StringBuilder sb = new StringBuilder();
        while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '-')) {
            sb.append(json.charAt(idx++));
        }
        try { return Integer.parseInt(sb.toString()); } catch (Exception e) { return defVal; }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String getDeviceIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("127.")) return ip;
                    }
                }
            }
        } catch (Exception e) {}
        return "127.0.0.1";
    }
}
