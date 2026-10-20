package com.pickixo.vpn.sstp;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A small MS-SSTP client: PPP (LCP, PAP / CHAP-MD5 / MS-CHAPv2, IPCP) carried over SSTP over TLS.
 *
 * Usage: {@link #connect} blocks until the tunnel is up and returns the address the server assigned.
 * Afterwards incoming IPv4 packets are delivered to the {@link Listener} on a background thread and
 * outgoing packets are sent with {@link #sendIp}. Call {@link #tick} about once a second for keepalives.
 *
 * Pure Java with no Android dependencies, so it can be tested on a desktop JVM.
 */
public final class SstpClient implements Closeable {

    public interface SocketProtector { boolean protect(Socket socket); }

    /** Test hook: restrict TLS cipher suites (e.g. to Android's defaults). Null = platform default. */
    public static volatile String[] forcedCipherSuites;

    /** Optional diagnostics sink (Logcat on Android, stdout in tests). Null = silent. */
    public static volatile java.util.function.Consumer<String> debug;

    private void d(String msg) {
        java.util.function.Consumer<String> c = debug;
        if (c != null) c.accept(host + ":" + port + " " + msg);
    }

    private static String hexDump(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < Math.min(b.length, off + Math.min(len, 64)); i++) sb.append(String.format("%02x", b[i] & 0xff));
        if (len > 64) sb.append("…");
        return sb.toString();
    }

    public interface Listener {
        void onIpPacket(byte[] buf, int off, int len);
        void onClosed(String reason);
    }

    public static final class Result {
        public final byte[] address;
        public final byte[] dns1;
        public final byte[] dns2;
        public final int mtu;
        public final String auth;

        Result(byte[] address, byte[] dns1, byte[] dns2, int mtu, String auth) {
            this.address = address; this.dns1 = dns1; this.dns2 = dns2; this.mtu = mtu; this.auth = auth;
        }
    }

    // ---- protocol constants ----
    private static final int PROTO_IP = 0x0021, PROTO_LCP = 0xC021, PROTO_PAP = 0xC023,
            PROTO_CHAP = 0xC223, PROTO_IPCP = 0x8021;
    private static final int CONF_REQ = 1, CONF_ACK = 2, CONF_NAK = 3, CONF_REJ = 4, TERM_REQ = 5,
            TERM_ACK = 6, CODE_REJ = 7, PROTO_REJ = 8, ECHO_REQ = 9, ECHO_REP = 10;
    private static final int MSG_CONNECT_REQUEST = 1, MSG_CONNECT_ACK = 2, MSG_CONNECT_NAK = 3,
            MSG_CONNECTED = 4, MSG_ABORT = 5, MSG_DISCONNECT = 6, MSG_DISCONNECT_ACK = 7,
            MSG_ECHO_REQUEST = 8, MSG_ECHO_RESPONSE = 9;
    private static final int ATTR_ENCAP_PROTO = 1, ATTR_CRYPTO_BINDING = 3, ATTR_CRYPTO_BINDING_REQ = 4;
    private static final int AUTH_NONE = 0, AUTH_PAP = 1, AUTH_CHAP_MD5 = 2, AUTH_MSCHAPV2 = 3;
    private static final int MAX_MTU = 1400;
    private static final long RETRANSMIT_MS = 3000, ECHO_IDLE_MS = 20_000, DEAD_MS = 60_000;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final SecureRandom random = new SecureRandom();

    private Socket raw;
    private SSLSocket ssl;
    private DataInputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed;
    private volatile boolean closing;
    private volatile String closeReason;
    private volatile long lastReceive, lastSend;
    private volatile Listener listener;

    // ---- negotiation state, guarded by "this" ----
    private final CountDownLatch done = new CountDownLatch(1);
    private boolean established;
    private String failure;
    private byte[] certHash256, certHash1;
    private byte[] bindingNonce;
    private int bindingHashMask;
    private int nextId = 1;
    private final int magic = new SecureRandom().nextInt() | 1;

    private int lcpReqId = -1;
    private long lcpReqSent;
    private int lcpRetries;
    private boolean lcpLocalOpen, lcpRemoteOpen;
    private boolean sendMru = true, sendMagic = true;
    private int myMru = 1500;
    private int peerMru = 1500;

    private int authType = AUTH_NONE;
    private boolean authStarted, authDone;
    private int papId;
    private long papSent;
    private int papRetries;
    private byte[] chapChallenge, chapPeerChallenge, chapNtResponse;
    private byte[] hlak = new byte[32];

    private int ipcpReqId = -1;
    private long ipcpReqSent;
    private int ipcpRetries;
    private boolean ipcpLocalOpen, ipcpRemoteOpen;
    private byte[] myIp = new byte[4], dns1 = new byte[4], dns2 = new byte[4];
    private boolean askDns1 = true, askDns2 = true;

    public SstpClient(String host, int port, String user, String password) {
        this.host = host; this.port = port; this.user = user; this.password = password;
    }

    // =====================================================================================
    // Public API
    // =====================================================================================

    public Result connect(SocketProtector protector, Listener listener, int connectTimeoutMs, int handshakeTimeoutMs)
            throws IOException {
        this.listener = listener;
        long deadline = System.currentTimeMillis() + handshakeTimeoutMs;
        try {
            raw = new Socket();
            if (protector != null && !protector.protect(raw)) throw new IOException("Could not protect socket");
            raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            if (closed || closing) throw new IOException("Cancelled");
            d("tcp connected");
            raw.setTcpNoDelay(true);
            // A server that accepts TCP but never answers TLS (typical when a firewall blocks VPNs)
            // should fail fast so the next server can be tried.
            raw.setSoTimeout(Math.min(handshakeTimeoutMs, 8000));

            ssl = (SSLSocket) trustAllContext().getSocketFactory().createSocket(raw, host, port, true);
            ssl.setUseClientMode(true);
            String[] forced = forcedCipherSuites;
            if (forced != null) ssl.setEnabledCipherSuites(forced);
            ssl.startHandshake();
            Certificate[] chain = ssl.getSession().getPeerCertificates();
            byte[] der = chain[0].getEncoded();
            certHash256 = MessageDigest.getInstance("SHA-256").digest(der);
            certHash1 = MessageDigest.getInstance("SHA-1").digest(der);
            d("tls " + ssl.getSession().getProtocol() + " " + ssl.getSession().getCipherSuite());

            if (closed || closing) throw new IOException("Cancelled");
            in = new DataInputStream(new java.io.BufferedInputStream(ssl.getInputStream(), 16384));
            out = ssl.getOutputStream();

            httpHandshake();
            sstpCallConnect();

            ssl.setSoTimeout(0);
            lastReceive = System.currentTimeMillis();
            Thread reader = new Thread(this::readLoop, "sstp-reader");
            reader.setDaemon(true);
            reader.start();

            synchronized (this) { sendLcpConfReq(); }

            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new IOException("Server did not finish negotiation in time");
                if (done.await(Math.min(left, 500), TimeUnit.MILLISECONDS)) break;
                synchronized (this) { retransmit(); }
            }
            synchronized (this) {
                if (failure != null) throw new IOException(failure);
                int mtu = Math.min(MAX_MTU, peerMru);
                return new Result(myIp.clone(), isZero(dns1) ? null : dns1.clone(), isZero(dns2) ? null : dns2.clone(),
                        mtu, authName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly("interrupted");
            throw new IOException("Interrupted");
        } catch (GeneralSecurityException e) {
            closeQuietly(e.toString());
            throw new IOException("TLS/crypto error: " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(e.toString());
            throw e;
        }
    }

    /** Sends one IPv4 packet into the tunnel. */
    public void sendIp(byte[] buf, int off, int len) throws IOException {
        if (len <= 0 || len > MAX_MTU + 100) return;
        writeData(PROTO_IP, buf, off, len);
    }

    /** Call about once a second. Returns false once the tunnel is dead. */
    public boolean tick() {
        if (closed || closing) return false;
        long now = System.currentTimeMillis();
        if (now - lastReceive > DEAD_MS) {
            closeQuietly("Server stopped responding");
            return false;
        }
        if (now - lastReceive > ECHO_IDLE_MS && now - lastSend > ECHO_IDLE_MS / 2) {
            try { writeControl(MSG_ECHO_REQUEST, null); } catch (IOException e) { closeQuietly(e.toString()); return false; }
        }
        return true;
    }

    public boolean isAlive() { return !closed && !closing; }

    public String closeReason() { return closeReason; }

    /**
     * Closes the tunnel. Safe to call from any thread, including Android's main thread: the goodbye
     * messages and socket shutdown are network I/O, so they always run on a background thread.
     */
    @Override
    public void close() {
        if (closed || closing) return;
        closing = true;
        Thread t = new Thread(() -> {
            try {
                synchronized (this) {
                    if (established && !closed) {
                        byte[] term = {TERM_REQ, (byte) nextId(), 0, 4};
                        writeData(PROTO_LCP, term, 0, 4);
                        writeControl(MSG_DISCONNECT, null);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
            closeQuietly("closed by user");
        }, "sstp-close");
        t.setDaemon(true);
        t.start();
    }

    /** True once close() was requested (the socket may still be shutting down). */
    public boolean isClosing() { return closing || closed; }

    // =====================================================================================
    // TLS, HTTP and SSTP control phase
    // =====================================================================================

    private static SSLContext trustAllContext() throws GeneralSecurityException {
        // VPN Gate servers use self-signed certificates and there is no CA to check them against.
        // Traffic is still encrypted with TLS; see README for this limitation.
        TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
        return ctx;
    }

    private void httpHandshake() throws IOException {
        String req = "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n"
                + "Content-Length: 18446744073709551615\r\n"
                + "Host: " + host + "\r\n"
                + "SSTPCORRELATIONID: {" + UUID.randomUUID().toString().toUpperCase() + "}\r\n"
                + "\r\n";
        synchronized (writeLock) {
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) throw new EOFException("Server closed connection during HTTP handshake");
            head.write(c);
            if (head.size() > 8192) throw new IOException("HTTP response too long");
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = (c == '\r') ? 1 : 0;
        }
        String status = head.toString("US-ASCII").split("\r\n", 2)[0];
        d("http " + status);
        String[] parts = status.split(" ");
        if (parts.length < 2 || !parts[1].equals("200")) throw new IOException("Not an SSTP server (" + status + ")");
    }

    private void sstpCallConnect() throws IOException {
        byte[] attr = {0, ATTR_ENCAP_PROTO, 0, 6, 0, 1};
        writeControl(MSG_CONNECT_REQUEST, new byte[][]{attr});
        for (int i = 0; i < 10; i++) {
            byte[] pkt = readPacket();
            if ((pkt[1] & 1) == 0) continue; // data before ack: ignore
            int type = u16(pkt, 4);
            if (type == MSG_CONNECT_NAK) throw new IOException("Server refused SSTP connection");
            if (type == MSG_ABORT || type == MSG_DISCONNECT) throw new IOException("Server aborted SSTP connection");
            if (type != MSG_CONNECT_ACK) continue;
            for (byte[] a : attributes(pkt)) {
                if ((a[1] & 0xff) == ATTR_CRYPTO_BINDING_REQ && a.length >= 40) {
                    bindingHashMask = a[7] & 0xff;
                    bindingNonce = Arrays.copyOfRange(a, 8, 40);
                }
            }
            if (bindingNonce == null) throw new IOException("Server sent no crypto binding request");
            d("sstp call connect ack, hash mask " + bindingHashMask);
            return;
        }
        throw new IOException("No SSTP Call Connect Ack");
    }

    private void sendCallConnected() throws IOException {
        boolean sha256 = (bindingHashMask & 2) != 0;
        byte[] p = new byte[112];
        p[0] = 0x10; p[1] = 1; p[2] = 0; p[3] = 112;
        p[4] = 0; p[5] = MSG_CONNECTED; p[6] = 0; p[7] = 1;
        p[8] = 0; p[9] = ATTR_CRYPTO_BINDING; p[10] = 0; p[11] = 104;
        p[15] = (byte) (sha256 ? 2 : 1);
        System.arraycopy(bindingNonce, 0, p, 16, 32);
        byte[] ch = sha256 ? certHash256 : certHash1;
        System.arraycopy(ch, 0, p, 48, ch.length);
        try {
            String alg = sha256 ? "HmacSHA256" : "HmacSHA1";
            int len = sha256 ? 32 : 20;
            Mac mac = Mac.getInstance(alg);
            mac.init(new SecretKeySpec(hlak, alg));
            mac.update("SSTP inner method derived CMK".getBytes(StandardCharsets.US_ASCII));
            mac.update(new byte[]{(byte) len, 0, 1});
            byte[] cmk = mac.doFinal();
            mac.init(new SecretKeySpec(cmk, alg));
            byte[] cmac = mac.doFinal(p);
            System.arraycopy(cmac, 0, p, 80, cmac.length);
        } catch (GeneralSecurityException e) {
            throw new IOException("Crypto binding failed", e);
        }
        writeRaw(p, 0, p.length);
    }

    // =====================================================================================
    // Reading
    // =====================================================================================

    private byte[] readPacket() throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int len = ((in.readUnsignedByte() & 0x0f) << 8) | in.readUnsignedByte();
        if (b0 != 0x10 || len < 4) throw new IOException("Bad SSTP packet header");
        byte[] p = new byte[len];
        p[0] = (byte) b0; p[1] = (byte) b1; p[2] = (byte) (len >> 8); p[3] = (byte) len;
        in.readFully(p, 4, len - 4);
        lastReceive = System.currentTimeMillis();
        return p;
    }

    private void readLoop() {
        try {
            while (!closed) {
                byte[] p = readPacket();
                if ((p[1] & 1) != 0) handleControl(p);
                else handleData(p);
            }
        } catch (IOException e) {
            closeQuietly(closed ? closeReason : "Connection lost: " + e.getMessage());
        } catch (Throwable e) {
            closeQuietly("Protocol error: " + e);
        }
    }

    private void handleControl(byte[] p) throws IOException {
        if (p.length < 8) return;
        d("rx sstp control " + u16(p, 4));
        switch (u16(p, 4)) {
            case MSG_ECHO_REQUEST:
                writeControl(MSG_ECHO_RESPONSE, null);
                break;
            case MSG_DISCONNECT:
                writeControl(MSG_DISCONNECT_ACK, null);
                closeQuietly("Server disconnected");
                break;
            case MSG_ABORT:
                closeQuietly("Server aborted the connection");
                break;
            default:
                break;
        }
    }

    private void handleData(byte[] p) throws IOException {
        int off = 4;
        if (p.length >= off + 2 && (p[off] & 0xff) == 0xFF && (p[off + 1] & 0xff) == 0x03) off += 2;
        if (p.length < off + 1) return;
        int proto;
        if ((p[off] & 1) == 1) { proto = p[off] & 0xff; off += 1; } // protocol field compression
        else { if (p.length < off + 2) return; proto = u16(p, off); off += 2; }

        if (proto != PROTO_IP) d(String.format("rx %04x %s", proto, hexDump(p, off, p.length - off)));
        if (proto == PROTO_IP) {
            Listener l = listener;
            boolean up;
            synchronized (this) { up = established; }
            if (up && l != null && p.length > off) {
                try { l.onIpPacket(p, off, p.length - off); } catch (RuntimeException e) { d("onIpPacket error: " + e); }
            }
            return;
        }
        synchronized (this) {
            switch (proto) {
                case PROTO_LCP: onLcp(p, off); break;
                case PROTO_PAP: onPap(p, off); break;
                case PROTO_CHAP: onChap(p, off); break;
                case PROTO_IPCP: onIpcp(p, off); break;
                default: sendProtocolReject(proto, p, off); break;
            }
        }
    }

    // =====================================================================================
    // PPP: LCP
    // =====================================================================================

    private void sendLcpConfReq() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (sendMru) { o.write(1); o.write(4); o.write(myMru >> 8); o.write(myMru); }
        if (sendMagic) { o.write(5); o.write(6); o.write(magic >>> 24); o.write(magic >>> 16); o.write(magic >>> 8); o.write(magic); }
        lcpReqId = nextId();
        lcpReqSent = System.currentTimeMillis();
        sendCp(PROTO_LCP, CONF_REQ, lcpReqId, o.toByteArray());
    }

    private void onLcp(byte[] p, int off) throws IOException {
        if (p.length < off + 4) return;
        int code = p[off] & 0xff, id = p[off + 1] & 0xff;
        int len = Math.min(u16(p, off + 2), p.length - off);
        if (len < 4) return;
        int dOff = off + 4, dLen = len - 4;
        switch (code) {
            case CONF_REQ: onLcpPeerConfReq(id, p, dOff, dLen); break;
            case CONF_ACK:
                if (id == lcpReqId) { lcpLocalOpen = true; lcpReqId = -1; maybeStartAuth(); }
                break;
            case CONF_NAK:
                if (id != lcpReqId) break;
                for (int[] o : options(p, dOff, dLen)) {
                    if (o[0] == 1 && o[2] == 2) myMru = Math.max(576, Math.min(1500, u16(p, o[1])));
                }
                sendLcpConfReq();
                break;
            case CONF_REJ:
                if (id != lcpReqId) break;
                for (int[] o : options(p, dOff, dLen)) {
                    if (o[0] == 1) sendMru = false;
                    if (o[0] == 5) sendMagic = false;
                }
                sendLcpConfReq();
                break;
            case TERM_REQ:
                sendCp(PROTO_LCP, TERM_ACK, id, new byte[0]);
                fail("Server ended the PPP session");
                break;
            case TERM_ACK:
                fail("PPP session terminated");
                break;
            case ECHO_REQ: {
                byte[] reply = new byte[Math.max(4, dLen)];
                reply[0] = (byte) (magic >>> 24); reply[1] = (byte) (magic >>> 16);
                reply[2] = (byte) (magic >>> 8); reply[3] = (byte) magic;
                if (dLen > 4) System.arraycopy(p, dOff + 4, reply, 4, dLen - 4);
                sendCp(PROTO_LCP, ECHO_REP, id, reply);
                break;
            }
            case ECHO_REP: case PROTO_REJ: case CODE_REJ: case 11 /* discard */:
                break;
            default: {
                byte[] rej = Arrays.copyOfRange(p, off, off + len);
                sendCp(PROTO_LCP, CODE_REJ, nextId(), rej);
            }
        }
    }

    private void onLcpPeerConfReq(int id, byte[] p, int dOff, int dLen) throws IOException {
        ByteArrayOutputStream rej = new ByteArrayOutputStream(), nak = new ByteArrayOutputStream();
        int chosenAuth = AUTH_NONE;
        int mru = 1500;
        for (int[] o : options(p, dOff, dLen)) {
            int type = o[0], vo = o[1], vl = o[2];
            switch (type) {
                case 1: if (vl == 2) mru = u16(p, vo); break;          // MRU
                case 2: break;                                       // ACCM: meaningless over SSTP, accept
                case 5: break;                                       // magic number
                case 3: {                                            // authentication protocol
                    int ap = vl >= 2 ? u16(p, vo) : 0;
                    if (ap == PROTO_PAP && vl == 2) chosenAuth = AUTH_PAP;
                    else if (ap == PROTO_CHAP && vl == 3 && (p[vo + 2] & 0xff) == 0x81) chosenAuth = AUTH_MSCHAPV2;
                    else if (ap == PROTO_CHAP && vl == 3 && (p[vo + 2] & 0xff) == 0x05) chosenAuth = AUTH_CHAP_MD5;
                    else nak.write(new byte[]{3, 5, (byte) 0xC2, 0x23, (byte) 0x81}, 0, 5);
                    break;
                }
                default: rej.write(p, vo - 2, vl + 2);              // PFC, ACFC, anything else
            }
        }
        if (rej.size() > 0) sendCp(PROTO_LCP, CONF_REJ, id, rej.toByteArray());
        else if (nak.size() > 0) sendCp(PROTO_LCP, CONF_NAK, id, nak.toByteArray());
        else {
            sendCp(PROTO_LCP, CONF_ACK, id, Arrays.copyOfRange(p, dOff, dOff + dLen));
            peerMru = mru;
            authType = chosenAuth;
            lcpRemoteOpen = true;
            maybeStartAuth();
        }
    }

    // =====================================================================================
    // PPP: authentication
    // =====================================================================================

    private void maybeStartAuth() throws IOException {
        if (!lcpLocalOpen || !lcpRemoteOpen || authStarted) return;
        authStarted = true;
        if (authType == AUTH_PAP) sendPap();
        else if (authType == AUTH_NONE) onAuthSuccess();
        // CHAP: wait for the server's challenge
    }

    private void sendPap() throws IOException {
        byte[] u = user.getBytes(StandardCharsets.UTF_8), pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[2 + u.length + pw.length];
        d[0] = (byte) u.length;
        System.arraycopy(u, 0, d, 1, u.length);
        d[1 + u.length] = (byte) pw.length;
        System.arraycopy(pw, 0, d, 2 + u.length, pw.length);
        papId = nextId();
        papSent = System.currentTimeMillis();
        sendCp(PROTO_PAP, 1, papId, d);
    }

    private void onPap(byte[] p, int off) throws IOException {
        if (p.length < off + 4 || authDone || authType != AUTH_PAP) return;
        int code = p[off] & 0xff;
        if ((p[off + 1] & 0xff) != papId) return;
        if (code == 2) onAuthSuccess();
        else if (code == 3) fail("Login rejected by server (PAP)");
    }

    private void onChap(byte[] p, int off) throws IOException {
        if (p.length < off + 4) return;
        int code = p[off] & 0xff, id = p[off + 1] & 0xff;
        int len = Math.min(u16(p, off + 2), p.length - off);
        try {
            if (code == 1 && !authDone) {            // challenge
                int vs = p[off + 4] & 0xff;
                if (len < 5 + vs) return;
                byte[] challenge = Arrays.copyOfRange(p, off + 5, off + 5 + vs);
                byte[] name = user.getBytes(StandardCharsets.UTF_8);
                byte[] value;
                if (authType == AUTH_MSCHAPV2) {
                    if (vs != 16) { fail("Bad MS-CHAPv2 challenge"); return; }
                    chapChallenge = challenge;
                    chapPeerChallenge = new byte[16];
                    random.nextBytes(chapPeerChallenge);
                    chapNtResponse = MsChapV2.ntResponse(challenge, chapPeerChallenge, user, password);
                    value = new byte[49];
                    System.arraycopy(chapPeerChallenge, 0, value, 0, 16);
                    System.arraycopy(chapNtResponse, 0, value, 24, 24);
                } else {
                    MessageDigest md5 = MessageDigest.getInstance("MD5");
                    md5.update((byte) id);
                    md5.update(password.getBytes(StandardCharsets.UTF_8));
                    md5.update(challenge);
                    value = md5.digest();
                }
                byte[] d = new byte[1 + value.length + name.length];
                d[0] = (byte) value.length;
                System.arraycopy(value, 0, d, 1, value.length);
                System.arraycopy(name, 0, d, 1 + value.length, name.length);
                sendCp(PROTO_CHAP, 2, id, d);
            } else if (code == 3 && !authDone) {     // success
                if (authType == AUTH_MSCHAPV2) {
                    String msg = new String(p, off + 4, Math.max(0, len - 4), StandardCharsets.US_ASCII);
                    String expect = MsChapV2.authenticatorResponse(password, chapNtResponse, chapPeerChallenge,
                            chapChallenge, user);
                    if (!msg.toUpperCase().startsWith(expect)) { fail("Server failed MS-CHAPv2 verification"); return; }
                    hlak = MsChapV2.hlak(password, chapNtResponse);
                }
                onAuthSuccess();
            } else if (code == 4) {
                fail("Login rejected by server");
            }
        } catch (GeneralSecurityException e) {
            fail("Authentication crypto error: " + e.getMessage());
        }
    }

    private void onAuthSuccess() throws IOException {
        authDone = true;
        sendCallConnected();
        sendIpcpConfReq();
    }

    // =====================================================================================
    // PPP: IPCP
    // =====================================================================================

    private void sendIpcpConfReq() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(3); o.write(6); o.write(myIp, 0, 4);
        if (askDns1) { o.write(129); o.write(6); o.write(dns1, 0, 4); }
        if (askDns2) { o.write(131); o.write(6); o.write(dns2, 0, 4); }
        ipcpReqId = nextId();
        ipcpReqSent = System.currentTimeMillis();
        sendCp(PROTO_IPCP, CONF_REQ, ipcpReqId, o.toByteArray());
    }

    private void onIpcp(byte[] p, int off) throws IOException {
        if (p.length < off + 4) return;
        int code = p[off] & 0xff, id = p[off + 1] & 0xff;
        int len = Math.min(u16(p, off + 2), p.length - off);
        if (len < 4) return;
        int dOff = off + 4, dLen = len - 4;
        switch (code) {
            case CONF_REQ: {
                ByteArrayOutputStream rej = new ByteArrayOutputStream();
                for (int[] o : options(p, dOff, dLen)) if (o[0] != 3) rej.write(p, o[1] - 2, o[2] + 2);
                if (rej.size() > 0) sendCp(PROTO_IPCP, CONF_REJ, id, rej.toByteArray());
                else {
                    sendCp(PROTO_IPCP, CONF_ACK, id, Arrays.copyOfRange(p, dOff, dOff + dLen));
                    ipcpRemoteOpen = true;
                    maybeEstablished();
                }
                break;
            }
            case CONF_NAK:
                if (id != ipcpReqId) break;
                for (int[] o : options(p, dOff, dLen)) {
                    if (o[2] != 4) continue;
                    byte[] v = Arrays.copyOfRange(p, o[1], o[1] + 4);
                    if (o[0] == 3) myIp = v; else if (o[0] == 129) dns1 = v; else if (o[0] == 131) dns2 = v;
                }
                sendIpcpConfReq();
                break;
            case CONF_REJ:
                if (id != ipcpReqId) break;
                for (int[] o : options(p, dOff, dLen)) {
                    if (o[0] == 3) { fail("Server would not assign an IP address"); return; }
                    if (o[0] == 129) askDns1 = false;
                    if (o[0] == 131) askDns2 = false;
                }
                sendIpcpConfReq();
                break;
            case CONF_ACK:
                if (id != ipcpReqId) break;
                if (isZero(myIp)) { fail("Server assigned no IP address"); return; }
                ipcpLocalOpen = true;
                ipcpReqId = -1;
                maybeEstablished();
                break;
            case TERM_REQ:
                sendCp(PROTO_IPCP, TERM_ACK, id, new byte[0]);
                fail("Server closed the IP session");
                break;
            default:
                break;
        }
    }

    private void maybeEstablished() {
        if (ipcpLocalOpen && ipcpRemoteOpen && !established) {
            established = true;
            done.countDown();
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private void retransmit() throws IOException {
        if (failure != null || established) return;
        long now = System.currentTimeMillis();
        if (lcpReqId >= 0 && now - lcpReqSent > RETRANSMIT_MS) {
            if (++lcpRetries > 10) { fail("No LCP response from server"); return; }
            sendLcpConfReq();
        }
        if (authType == AUTH_PAP && authStarted && !authDone && now - papSent > RETRANSMIT_MS) {
            if (++papRetries > 5) { fail("No login response from server"); return; }
            sendPap();
        }
        if (ipcpReqId >= 0 && now - ipcpReqSent > RETRANSMIT_MS) {
            if (++ipcpRetries > 10) { fail("No IPCP response from server"); return; }
            sendIpcpConfReq();
        }
    }

    private void sendProtocolReject(int proto, byte[] p, int off) throws IOException {
        if (!lcpLocalOpen || !lcpRemoteOpen) return;
        int infoLen = Math.min(p.length - off, Math.max(0, peerMru - 6));
        byte[] d = new byte[2 + infoLen];
        d[0] = (byte) (proto >> 8); d[1] = (byte) proto;
        System.arraycopy(p, off, d, 2, infoLen);
        sendCp(PROTO_LCP, PROTO_REJ, nextId(), d);
    }

    private synchronized void fail(String reason) {
        d("FAIL " + reason);
        if (failure == null) failure = reason;
        done.countDown();
        if (established) closeQuietly(reason);
    }

    private int nextId() {
        int id = nextId;
        nextId = (nextId + 1) & 0xff;
        return id;
    }

    private void sendCp(int proto, int code, int id, byte[] data) throws IOException {
        d(String.format("tx %04x code=%d id=%d %s", proto, code, id, hexDump(data, 0, data.length)));
        byte[] f = new byte[4 + data.length];
        f[0] = (byte) code; f[1] = (byte) id; f[2] = (byte) (f.length >> 8); f[3] = (byte) f.length;
        System.arraycopy(data, 0, f, 4, data.length);
        writeData(proto, f, 0, f.length);
    }

    private void writeData(int proto, byte[] buf, int off, int len) throws IOException {
        int total = 4 + 4 + len;
        if (total > 4095) throw new IOException("Packet too large for SSTP");
        byte[] p = new byte[total];
        p[0] = 0x10; p[1] = 0; p[2] = (byte) ((total >> 8) & 0x0f); p[3] = (byte) total;
        p[4] = (byte) 0xFF; p[5] = 0x03; p[6] = (byte) (proto >> 8); p[7] = (byte) proto;
        System.arraycopy(buf, off, p, 8, len);
        writeRaw(p, 0, total);
    }

    private void writeControl(int type, byte[][] attrs) throws IOException {
        int total = 8;
        if (attrs != null) for (byte[] a : attrs) total += a.length;
        byte[] p = new byte[total];
        p[0] = 0x10; p[1] = 1; p[2] = (byte) ((total >> 8) & 0x0f); p[3] = (byte) total;
        p[4] = (byte) (type >> 8); p[5] = (byte) type;
        int n = attrs == null ? 0 : attrs.length;
        p[6] = (byte) (n >> 8); p[7] = (byte) n;
        int pos = 8;
        if (attrs != null) for (byte[] a : attrs) { System.arraycopy(a, 0, p, pos, a.length); pos += a.length; }
        writeRaw(p, 0, total);
    }

    private void writeRaw(byte[] p, int off, int len) throws IOException {
        if (closed) throw new IOException("Tunnel closed");
        synchronized (writeLock) {
            out.write(p, off, len);
            out.flush();
        }
        lastSend = System.currentTimeMillis();
    }

    private static List<byte[]> attributes(byte[] pkt) {
        List<byte[]> list = new ArrayList<>();
        int n = u16(pkt, 6), pos = 8;
        for (int i = 0; i < n && pos + 4 <= pkt.length; i++) {
            int len = u16(pkt, pos + 2) & 0x0fff;
            if (len < 4 || pos + len > pkt.length) break;
            list.add(Arrays.copyOfRange(pkt, pos, pos + len));
            pos += len;
        }
        return list;
    }

    /** Parses PPP options; each entry is {type, valueOffset, valueLength}. */
    private static List<int[]> options(byte[] p, int off, int len) {
        List<int[]> list = new ArrayList<>();
        int pos = off, end = Math.min(p.length, off + len);
        while (pos + 2 <= end) {
            int t = p[pos] & 0xff, l = p[pos + 1] & 0xff;
            if (l < 2 || pos + l > end) break;
            list.add(new int[]{t, pos + 2, l - 2});
            pos += l;
        }
        return list;
    }

    private static int u16(byte[] b, int off) { return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff); }

    private static boolean isZero(byte[] b) { for (byte x : b) if (x != 0) return false; return true; }

    private String authName() {
        switch (authType) {
            case AUTH_PAP: return "PAP";
            case AUTH_CHAP_MD5: return "CHAP-MD5";
            case AUTH_MSCHAPV2: return "MS-CHAPv2";
            default: return "none";
        }
    }

    private void closeQuietly(String reason) {
        d("closed: " + reason);
        boolean notify;
        synchronized (writeLock) {
            notify = !closed;
            closed = true;
            if (closeReason == null) closeReason = reason;
        }
        try { if (ssl != null) ssl.close(); } catch (IOException ignored) {}
        try { if (raw != null) raw.close(); } catch (IOException ignored) {}
        boolean wasUp;
        synchronized (this) {
            wasUp = established;
            if (failure == null && !established) failure = reason;
        }
        done.countDown();
        Listener l = listener;
        // Only report closes of an established tunnel; handshake failures surface as exceptions from connect().
        if (notify && wasUp && l != null) l.onClosed(closeReason);
    }
}
