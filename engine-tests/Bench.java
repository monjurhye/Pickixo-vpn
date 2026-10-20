package com.pickixo.vpn.sstp;
import java.util.concurrent.*;
/** Measures how fast the engine can receive tunnel traffic (server -> client), the download direction. */
public class Bench {
    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        final long[] bytes = {0}; final long[] t = {0, 0};
        CountDownLatch end = new CountDownLatch(1);
        SstpClient c = new SstpClient("127.0.0.1", port, "vpn", "vpn");
        c.connect(s -> true, new SstpClient.Listener() {
            public void onIpPacket(byte[] b, int o, int l) {
                if (l > 1000) { if (t[0] == 0) t[0] = System.nanoTime(); bytes[0] += l; t[1] = System.nanoTime(); }
                else if (l >= 24 && b[o + 20] == 'E') end.countDown();
            }
            public void onClosed(String r) { end.countDown(); }
        }, 3000, 15000);
        for (int i = 0; i < 3; i++) {
            byte[] ip = new byte[60]; ip[0] = 0x45; ip[12] = 10; ip[15] = 5; ip[16] = 1; ip[17] = 2; ip[18] = 3; ip[19] = 4;
            c.sendIp(ip, 0, ip.length);
        }
        end.await(120, TimeUnit.SECONDS);
        double secs = (t[1] - t[0]) / 1e9;
        System.out.printf("received %.1f MB in %.2f s = %.0f Mbps%n", bytes[0] / 1e6, secs, bytes[0] * 8 / 1e6 / secs);
        c.close();
        System.exit(0);
    }
}
