package com.youlong.redteam;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 最小 ADB host 客户端：直接连本机 adbd（127.0.0.1:<tcp port>），
 * 用自己的 RSA key 完成 AUTH，然后发送 shell: 服务。
 *
 * 仅支持 legacy TCP ADB（adb tcpip / service.adb.tcp.port），
 * 不支持 Android 11+ 无线调试的 TLS+配对端口（那种端口会回 A_STLS）。
 */
public final class AdbClient implements Closeable {

    private static final String TAG = "RedTeamAdb";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;
    private static final int A_VERSION = 0x01000000;
    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;

    private final AdbKeyPair key;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private int nextLocalId = 1;
    public final int port;

    public AdbClient(AdbKeyPair key, int port) {
        this.key = key;
        this.port = port;
    }

    public void connect() throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(120000); // 首次连接需要用户在手机上点「允许 USB 调试」
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());

        send(A_CNXN, A_VERSION, 4096, "host::".getBytes(StandardCharsets.US_ASCII));
        Message m = read();
        if (m.command == A_STLS) {
            throw new IOException("这是无线调试 TLS 端口，当前测试工具只支持 adb tcpip/legacy ADB");
        }
        if (m.command == A_AUTH) {
            if (m.arg0 == ADB_AUTH_TOKEN) {
                send(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(m.data));
                m = read();
            }
            // adbd 在 key 未授权时可能回 A_AUTH token 或 A_AUTH RSAPUBLICKEY；
            // 只要不是 CNXN，就把公钥发过去，手机会弹「允许 USB 调试」。
            if (m.command != A_CNXN) {
                send(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey);
                m = read(); // 等待用户允许
            }
        }
        if (m.command != A_CNXN) {
            throw new IOException("ADB 握手失败，command=0x" + Integer.toHexString(m.command));
        }
        socket.setSoTimeout(30000);
        Log.i(TAG, "ADB connected on 127.0.0.1:" + port);
    }

    /** 执行一条 shell 命令，返回 stdout+stderr。 */
    public String shell(String command) throws Exception {
        if (socket == null || !socket.isConnected()) throw new IOException("ADB 未连接");
        int localId = nextLocalId++;
        int remoteId = -1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] payload = ("shell:" + command).getBytes(StandardCharsets.UTF_8);
        send(A_OPEN, localId, 0, payload);

        while (true) {
            Message m = read();
            if (m.command == A_OKAY) {
                if (remoteId < 0) remoteId = m.arg0;
            } else if (m.command == A_WRTE) {
                if (remoteId < 0) remoteId = m.arg0;
                if (m.data != null && m.data.length > 0) out.write(m.data, 0, m.data.length);
                send(A_OKAY, localId, remoteId, null);
            } else if (m.command == A_CLSE) {
                break;
            }
        }
        if (remoteId >= 0) {
            try { send(A_CLSE, localId, remoteId, null); } catch (Throwable ignored) {}
        }
        return out.toString("UTF-8");
    }

    /** 执行一条原始 adb service（如 usb:、tcpip:0），返回设备回显。 */
    public String service(String service) throws Exception {
        if (socket == null || !socket.isConnected()) throw new IOException("ADB 未连接");
        int localId = nextLocalId++;
        int remoteId = -1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] payload = service.getBytes(StandardCharsets.UTF_8);
        send(A_OPEN, localId, 0, payload);

        while (true) {
            Message m = read();
            if (m.command == A_OKAY) {
                if (remoteId < 0) remoteId = m.arg0;
            } else if (m.command == A_WRTE) {
                if (remoteId < 0) remoteId = m.arg0;
                if (m.data != null && m.data.length > 0) out.write(m.data, 0, m.data.length);
                send(A_OKAY, localId, remoteId, null);
            } else if (m.command == A_CLSE) {
                break;
            }
        }
        if (remoteId >= 0) {
            try { send(A_CLSE, localId, remoteId, null); } catch (Throwable ignored) {}
        }
        return out.toString("UTF-8");
    }

    /** 从 getprop 和常见端口里找一个可连的 legacy ADB 端口。 */
    public static int resolvePort() {
        int prop = readProperty("service.adb.tcp.port");
        if (prop > 0) return prop;
        int[] candidates = new int[]{8056, 5555, 5037, 9242, 5556, 8080, 4444};
        for (int p : candidates) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", p), 300);
                return p;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static int readProperty(String name) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", name});
            byte[] bytes = readAll(p.getInputStream());
            p.waitFor();
            String value = new String(bytes, StandardCharsets.UTF_8).trim();
            return Integer.parseInt(value);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int checksum = 0;
        if (data != null) {
            for (byte b : data) checksum += (b & 0xff);
        }
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(length);
        header.putInt(checksum);
        header.putInt(command ^ 0xffffffff);
        output.write(header.array());
        if (data != null && data.length > 0) output.write(data);
        output.flush();
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        input.readFully(header);
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buf.getInt();
        int arg0 = buf.getInt();
        int arg1 = buf.getInt();
        int length = buf.getInt();
        int checksum = buf.getInt();
        int magic = buf.getInt();
        if (magic != (command ^ 0xffffffff)) {
            throw new IOException("ADB 消息 magic 校验失败");
        }
        byte[] data = null;
        if (length > 0) {
            data = new byte[length];
            input.readFully(data);
        }
        return new Message(command, arg0, arg1, data);
    }

    @Override
    public void close() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
    }

    private static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
