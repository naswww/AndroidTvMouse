package com.tv.remotemouse.v2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 极简 adb 客户端：只实现 CNXN / AUTH / OPEN / WRTE / OKAY / CLSE。
 * 用于在电视本地连 127.0.0.1:5555，拿一个 shell 通道。
 */
final class AdbClient {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private int localId = 1;

    AdbClient(String host, int port, int connectTimeout) throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), connectTimeout);
        sock.setSoTimeout(connectTimeout);
        in = sock.getInputStream();
        out = sock.getOutputStream();
    }

    void close() {
        try { sock.close(); } catch (IOException ignored) { }
    }

    private void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte b : data) sum += (b & 0xFF);
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd ^ 0xFFFFFFFF);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private int[] header() throws IOException {
        byte[] h = new byte[24];
        int off = 0;
        while (off < 24) {
            int r = in.read(h, off, 24 - off);
            if (r < 0) throw new IOException("连接被对端关闭");
            off += r;
        }
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        return new int[]{b.getInt(), b.getInt(), b.getInt(), b.getInt()};
    }

    private byte[] body(int len) throws IOException {
        byte[] d = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(d, off, len - off);
            if (r < 0) throw new IOException("连接被对端关闭");
            off += r;
        }
        return d;
    }

    /**
     * 完成握手。首次连接会触发电视上的授权弹窗，需人工点「允许」，
     * 所以这里要留足等待时间（建议 60 秒）。
     */
    void handshake(AdbKey key, int timeoutMs) throws Exception {
        sock.setSoTimeout(timeoutMs);
        send(A_CNXN, 0x01000000, 256 * 1024, "host::\0".getBytes("UTF-8"));
        int authCount = 0;
        StringBuilder dbg = new StringBuilder();
        while (true) {
            int[] h = header();
            int cmd = h[0];
            int len = h[3];
            byte[] data = len > 0 ? body(len) : null;
            if (cmd == A_CNXN) { debug = dbg + " -> CNXN OK"; return; }
            if (cmd == A_AUTH) {
                authCount++;
                dbg.append("AUTH(a0=").append(h[1]).append(",len=").append(len).append(") ");
                if (authCount == 1) {
                    byte[] sig = key.sign(data);
                    dbg.append("sig=").append(sig.length).append(" ");
                    debug = dbg.toString();
                    send(A_AUTH, 2, 0, sig);
                } else {
                    debug = dbg + "->PUBKEY";
                    send(A_AUTH, 3, 0, key.publicKeyPayload());
                }
            }
        }
    }

    static volatile String debug = "";

    /** 执行一条 shell 命令并返回输出。 */
    String shell(String cmd, int timeoutMs) throws IOException {
        return shell(cmd, timeoutMs, null);
    }

    interface Sink { void onData(String s); }

    /** 执行 shell 命令；sink 非空时边收边回调（用于长期挂着的守护进程）。 */
    String shell(String cmd, int timeoutMs, Sink sink) throws IOException {
        int lid = localId++;
        send(A_OPEN, lid, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        sock.setSoTimeout(timeoutMs > 0 ? timeoutMs : 0);
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        while (System.currentTimeMillis() < deadline) {
            int[] h;
            try {
                h = header();
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            int cmdId = h[0], a0 = h[1], a1 = h[2], len = h[3];
            byte[] data = len > 0 ? body(len) : null;
            if (cmdId == A_WRTE) {
                if (data != null) {
                    buf.write(data);
                    if (sink != null) sink.onData(new String(data, "UTF-8"));
                }
                send(A_OKAY, lid, a0, null);
            } else if (cmdId == A_CLSE) {
                if (a1 == lid) break;
            }
        }
        return buf.toString("UTF-8");
    }
}
