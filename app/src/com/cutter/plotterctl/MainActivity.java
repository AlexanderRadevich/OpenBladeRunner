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
    private android.widget.Button btnStop;
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
        btnStop = (android.widget.Button) findViewById(getResId("btnStop"));

        cutJob = new CutJob();

        btnStop.setOnClickListener(v -> {
            log("EMERGENCY STOP");
            if (webServer != null) webServer.stopCut();
        });

        log("PlotterCtl Web Server starting...");

        // Auto-connect serial
        connectSerial();
    }

    private int getResId(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
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

                // Query firmware
                Thread.sleep(100);
                String ver = protocol.queryVersion();
                if (ver != null) {
                    log("Firmware: " + ver);
                }

                // Start web server
                startWebServer();

            } catch (Exception e) {
                log("Serial error: " + e.getMessage());
                handler.post(() -> tvStatus.setText("Serial: ERROR - " + e.getMessage()));
                // Still start web server (for testing without plotter)
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
