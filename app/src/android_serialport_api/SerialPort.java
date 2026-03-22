package android_serialport_api;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SerialPort {

    public FileInputStream f3970a;
    public FileOutputStream f3971b;

    static {
        try {
            System.loadLibrary("serial_port");
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public SerialPort(File file, int baudrate, int flags) throws IOException {
        FileDescriptor open = open(file.getAbsolutePath(), baudrate, flags);
        if (open == null) {
            throw new IOException("open() returned null");
        }
        this.f3970a = new FileInputStream(open);
        this.f3971b = new FileOutputStream(open);
    }

    private static native FileDescriptor open(String path, int baudrate, int flags);

    public native void close();

    public void a() {
        try {
            this.f3970a.close();
            this.f3971b.close();
        } catch (IOException e) {
            // ignore
        }
        this.f3970a = null;
        this.f3971b = null;
    }

    public InputStream c() {
        return this.f3970a;
    }

    public OutputStream d() {
        return this.f3971b;
    }
}
