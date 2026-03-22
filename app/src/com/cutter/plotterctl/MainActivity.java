package com.cutter.plotterctl;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import android_serialport_api.SerialPort;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String SERIAL_DEVICE = "/dev/ttyS1";
    private static final int BAUD_RATE = 38400;
    private static final int WEB_PORT = 80;

    private TextView tvUrl, tvStatus, tvLog;
    private ScrollView scrollLog;
    private Handler handler;

    private SerialPort serialPort;
    private PlotterProtocol protocol;
    private WebServer webServer;
    private CutJob cutJob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("activity_main", "layout", getPackageName()));

        handler = new Handler(Looper.getMainLooper());

        tvUrl = (TextView) findViewById(getResId("tvUrl"));
        tvStatus = (TextView) findViewById(getResId("tvStatus"));
        tvLog = (TextView) findViewById(getResId("tvLog"));
        scrollLog = (ScrollView) findViewById(getResId("scrollLog"));

        cutJob = new CutJob();

        // Emergency stop
        findViewById(getResId("btnStop")).setOnClickListener(v -> {
            log("EMERGENCY STOP");
            if (webServer != null) webServer.stopCut();
        });

        // Roller controls (8000 units = 200mm)
        findViewById(getResId("btnRollerFwd")).setOnClickListener(v -> moveRoller(8000));
        findViewById(getResId("btnRollerBack")).setOnClickListener(v -> moveRoller(-8000));
        findViewById(getResId("btnHome")).setOnClickListener(v -> sendHome());

        log("PlotterCtl Web Server starting...");
        connectSerial();
    }

    private int getResId(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
    }

    /**
     * Move roller using the cut protocol with a PU-only HPGL command.
     * Uses axis swap + negative X (same as cutting) so roller direction is correct.
     * delta in mm: positive = load (pull paper in from back), negative = eject.
     */
    private void moveRoller(int deltaMm) {
        if (protocol == null) { log("Not connected"); return; }
        log("Roller: " + (deltaMm > 0 ? "LOAD " : "EJECT ") + Math.abs(deltaMm) + "mm");
        new Thread(() -> {
            try {
                // Build HPGL with just a PU move.
                // In HPGL file coords: Y = paper direction (before axis swap).
                // After swap: X = roller. Negative X = inward.
                // So we use Y in the HPGL, swap makes it X, offset negates it.
                int units = Math.abs(deltaMm) * 40;
                String hpgl;
                if (deltaMm > 0) {
                    // Load: move paper inward — use negative X after swap
                    // In pre-swap HPGL: PU0,<dist> → after swap: PU<dist>,0 → after negate: PU-<dist>,0
                    hpgl = "IN;PA;PU0," + units + ";";
                } else {
                    // Eject: move paper outward — positive X after swap
                    // Just go toward origin
                    hpgl = "IN;PA;PU0,0;";
                }

                // Apply same transforms as cutting: swap axes, negate X
                hpgl = HpglParser.swapAxes(hpgl);
                hpgl = HpglParser.applyOffset(hpgl, -units, 0); // negate X for inward direction

                log("HPGL: " + hpgl);
                byte[] payload = hpgl.getBytes("US-ASCII");
                sendCutData(payload);
            } catch (Exception e) {
                log("Roller error: " + e.getMessage());
            }
        }).start();
    }

    /** Send home: reset knife + eject via cut protocol. */
    private void sendHome() {
        if (protocol == null) { log("Not connected"); return; }
        log("HOME - reset knife");
        new Thread(() -> {
            try {
                // Reset knife
                byte[] pkt = PlotterProtocol.buildSet(0x1B, null);
                protocol.sendDirect(pkt);
                Thread.sleep(500);
                // Send a PU0,0 via cut protocol to return to origin
                String hpgl = "IN;PA;PU0,0;";
                byte[] payload = hpgl.getBytes("US-ASCII");
                sendCutData(payload);
                log("Home done");
            } catch (Exception e) {
                log("Home error: " + e.getMessage());
            }
        }).start();
    }

    /** Send raw HPGL data through the cut protocol */
    private void sendCutData(byte[] payload) throws Exception {
        String payloadHex = PlotterProtocol.bytesToHex(payload);

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

        synchronized (protocol) {
            protocol.drain();
            byte[] headerPacket = hexToBytes(headerHex.toString());
            protocol.send(headerPacket);
            byte[] resp = protocol.readResponse(5000);
            if (resp == null) { log("No header response"); return; }
        }
        Thread.sleep(200);

        // Single chunk
        String seqHex = "00"; // last (and only) chunk
        int chunkByteLen = payloadHex.length() / 2;
        String lenLoHex = String.format("%02X", chunkByteLen & 0xFF);
        String lenHiHex = String.format("%02X", (chunkByteLen >> 8) & 0xFF);

        StringBuilder pktHex = new StringBuilder("5AA5CC");
        pktHex.append(seqHex).append("30");
        pktHex.append(lenLoHex).append(lenHiHex);
        pktHex.append(payloadHex);

        String chkInput = "CC" + seqHex + "30" + lenLoHex + lenHiHex + payloadHex;
        int sum = 0;
        byte[] chkBytes = hexToBytes(chkInput);
        for (byte b : chkBytes) sum += (b & 0xFF);
        pktHex.append(String.format("%02X", sum & 0xFF));
        pktHex.append("0D0A");

        synchronized (protocol) {
            protocol.send(hexToBytes(pktHex.toString()));
            protocol.readResponse(5000);
        }
        log("Move complete");
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] result = new byte[len];
        for (int i = 0; i < len; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private void connectSerial() {
        new Thread(() -> {
            try {
                log("Opening " + SERIAL_DEVICE + "...");
                serialPort = new SerialPort(new File(SERIAL_DEVICE), BAUD_RATE, 0);
                OutputStream os = serialPort.f3971b;
                InputStream is = serialPort.f3970a;
                protocol = new PlotterProtocol(os, is);

                handler.post(() -> tvStatus.setText("Serial: CONNECTED"));
                log("Serial connected");

                Thread.sleep(100);
                String ver = protocol.queryVersion();
                if (ver != null) log("Firmware: " + ver);

                startWebServer();
            } catch (Exception e) {
                log("Serial error: " + e.getMessage());
                handler.post(() -> tvStatus.setText("Serial: ERROR - " + e.getMessage()));
                startWebServer();
            }
        }).start();
    }

    private void startWebServer() {
        webServer = new WebServer(WEB_PORT, protocol, cutJob);
        webServer.setListener(msg -> log(msg));
        webServer.start();

        String ip = WebServer.getDeviceIp();
        String url = "http://" + ip + (WEB_PORT == 80 ? "" : ":" + WEB_PORT);
        handler.post(() -> tvUrl.setText(url));
        log("Open in browser: " + url);
    }

    private void log(final String msg) {
        handler.post(() -> {
            tvLog.append(msg + "\n");
            scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        if (webServer != null) webServer.stop();
        if (serialPort != null) serialPort.a();
        super.onDestroy();
    }
}
