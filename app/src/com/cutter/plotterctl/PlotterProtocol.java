package com.cutter.plotterctl;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Protocol handler for MyScreen CUT&USE plotter.
 *
 * Packet format: [5A A5] [TYPE] [CMD_HI CMD_LO] [LEN_HI LEN_LO] [DATA...] [CHK1 CHK2] [0D 0A]
 * Types: AA=query, BB=set, CC=cut data
 * Checksum: SHA-256 based with secret key "hsznqmji"
 */
public class PlotterProtocol {

    public static final byte[] HEADER = {0x5A, (byte) 0xA5};
    public static final byte TYPE_QUERY = (byte) 0xAA;
    public static final byte TYPE_SET = (byte) 0xBB;
    public static final byte TYPE_CUT = (byte) 0xCC;

    // Secret key for checksum calculation
    private static final byte[] SECRET_KEY = "hsznqmji".getBytes();

    // Query commands
    public static final int CMD_QUERY_VERSION = 0x21;
    public static final int CMD_QUERY_10 = 0x10;
    public static final int CMD_QUERY_11 = 0x11;
    public static final int CMD_QUERY_12 = 0x12;
    public static final int CMD_QUERY_13 = 0x13;
    public static final int CMD_QUERY_18 = 0x18;
    public static final int CMD_QUERY_19 = 0x19;
    public static final int CMD_QUERY_20 = 0x20;
    public static final int CMD_QUERY_22 = 0x22;
    public static final int CMD_QUERY_23 = 0x23;
    public static final int CMD_QUERY_25 = 0x25;
    public static final int CMD_QUERY_34 = 0x34;
    public static final int CMD_QUERY_36 = 0x36;
    public static final int CMD_QUERY_38 = 0x38;
    public static final int CMD_QUERY_39 = 0x39;
    public static final int CMD_QUERY_3A = 0x3A;
    public static final int CMD_QUERY_3B = 0x3B;
    public static final int CMD_QUERY_3C = 0x3C;
    public static final int CMD_QUERY_3E = 0x3E;
    public static final int CMD_QUERY_4C = 0x4C;
    public static final int CMD_QUERY_53 = 0x53;

    // Set commands
    public static final int CMD_RESET_KNIFE = 0x1B;
    public static final int CMD_SET_SPEED = 0x34;
    public static final int CMD_SET_WIDE_FORMAT = 0x3E;

    // Cut commands
    public static final int CMD_CUT_DATA = 0x40;
    public static final int CMD_CUT_LONG = 0x0130;

    private OutputStream out;
    private InputStream in;

    public PlotterProtocol(OutputStream out, InputStream in) {
        this.out = out;
        this.in = in;
    }

    /**
     * Compute 2-byte SHA-256 based checksum.
     * Algorithm: SHA-256(commandBytes + SECRET_KEY), then:
     *   chk1 = sum of first 16 bytes of hash
     *   chk2 = sum of last 16 bytes of hash
     */
    public static byte[] computeChecksum(int[] commandInts) {
        // Build input: command bytes + secret key
        byte[] input = new byte[commandInts.length + SECRET_KEY.length];
        for (int i = 0; i < commandInts.length; i++) {
            input[i] = (byte) commandInts[i];
        }
        System.arraycopy(SECRET_KEY, 0, input, commandInts.length, SECRET_KEY.length);

        // SHA-256
        byte[] hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.reset();
            hash = md.digest(input);
        } catch (Exception e) {
            return new byte[]{0, 0};
        }

        // Sum first 16 and last 16 bytes
        byte chk1 = 0, chk2 = 0;
        for (int i = 0; i < 16; i++) {
            chk1 += hash[i];
            chk2 += hash[i + 16];
        }
        return new byte[]{chk1, chk2};
    }

    /**
     * Build a protocol packet with SHA-256 checksum.
     */
    public static byte[] buildPacket(byte type, int cmdId, byte[] data) {
        int dataLen = (data != null) ? data.length : 0;

        // Build command int array for checksum: type, cmdHi, cmdLo, lenHi, lenLo, data...
        int[] cmdInts = new int[5 + dataLen];
        cmdInts[0] = type & 0xFF;
        cmdInts[1] = (cmdId >> 8) & 0xFF;
        cmdInts[2] = cmdId & 0xFF;
        cmdInts[3] = (dataLen >> 8) & 0xFF;
        cmdInts[4] = dataLen & 0xFF;
        if (data != null) {
            for (int i = 0; i < dataLen; i++) {
                cmdInts[5 + i] = data[i] & 0xFF;
            }
        }

        // Compute checksum
        byte[] chk = computeChecksum(cmdInts);

        // Build packet: header(2) + cmdInts(5+dataLen) + checksum(2) + CRLF(2)
        byte[] packet = new byte[2 + cmdInts.length + 2 + 2];
        int idx = 0;
        packet[idx++] = HEADER[0];
        packet[idx++] = HEADER[1];
        for (int v : cmdInts) {
            packet[idx++] = (byte) v;
        }
        packet[idx++] = chk[0];
        packet[idx++] = chk[1];
        packet[idx++] = 0x0D;
        packet[idx++] = 0x0A;

        return packet;
    }

    public static byte[] buildQuery(int cmdId) {
        return buildPacket(TYPE_QUERY, cmdId, null);
    }

    public static byte[] buildSet(int cmdId, byte[] data) {
        return buildPacket(TYPE_SET, cmdId, data);
    }

    public static byte[] buildCutPacket(int cmdId, byte[] data) {
        return buildPacket(TYPE_CUT, cmdId, data);
    }

    public String send(byte[] packet) throws Exception {
        out.write(packet);
        out.flush();
        return bytesToHex(packet);
    }

    /**
     * Read response with timeout using blocking read on a daemon thread.
     */
    public byte[] readResponse(int timeoutMs) throws Exception {
        final byte[] buf = new byte[512];
        final int[] posHolder = {0};
        final boolean[] done = {false};

        Thread reader = new Thread(() -> {
            try {
                while (!done[0] && posHolder[0] < buf.length) {
                    int b = in.read();
                    if (b == -1) break;
                    buf[posHolder[0]++] = (byte) b;
                    if (posHolder[0] >= 2 &&
                        buf[posHolder[0] - 2] == 0x0D &&
                        buf[posHolder[0] - 1] == 0x0A) {
                        break;
                    }
                }
            } catch (Exception e) {
                // interrupted
            }
        });
        reader.setDaemon(true);
        reader.start();
        reader.join(timeoutMs);

        done[0] = true;
        if (reader.isAlive()) {
            reader.interrupt();
        }

        if (posHolder[0] == 0) return null;
        return Arrays.copyOf(buf, posHolder[0]);
    }

    public byte[] sendAndReceive(byte[] packet, int timeoutMs) throws Exception {
        send(packet);
        return readResponse(timeoutMs);
    }

    public String queryVersion() throws Exception {
        byte[] resp = sendAndReceive(buildQuery(CMD_QUERY_VERSION), 2000);
        if (resp == null) return null;
        return parseVersionResponse(resp);
    }

    public byte[] resetKnife() throws Exception {
        return sendAndReceive(buildSet(CMD_RESET_KNIFE, null), 5000);
    }

    public byte[] queryParam(int cmdId) throws Exception {
        return sendAndReceive(buildQuery(cmdId), 2000);
    }

    private String parseVersionResponse(byte[] resp) {
        if (resp.length < 9) return "Invalid response (too short): " + bytesToHex(resp);
        // Extract ASCII from payload
        StringBuilder sb = new StringBuilder();
        for (int i = 7; i < resp.length - 4; i++) {
            byte b = resp[i];
            if (b >= 0x20 && b <= 0x7E) {
                sb.append((char) b);
            }
        }
        return sb.length() > 0 ? sb.toString() : bytesToHex(resp);
    }

    /**
     * Verify response checksum.
     */
    public static boolean verifyChecksum(byte[] resp) {
        if (resp == null || resp.length < 6) return false;
        int len = resp.length - 6; // exclude header(2) + chk(2) + crlf(2)
        int[] cmdInts = new int[len];
        for (int i = 0; i < len; i++) {
            cmdInts[i] = resp[i + 2] & 0xFF;
        }
        byte[] chk = computeChecksum(cmdInts);
        return chk[0] == resp[resp.length - 4] && chk[1] == resp[resp.length - 3];
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
