package com.pickixo.vpn.sstp;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Desktop test: RFC vectors + a full session against the Python test server. */
public class EngineTest {
    static int fails = 0;
    static void check(String name, boolean ok) {
        System.out.println("CHECK " + name + ": " + (ok ? "PASS" : "FAIL"));
        if (!ok) fails++;
    }
    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("vectors")) {
            check("md4 empty", MsChapV2.hex(Md4.digest(new byte[0])).equals("31D6CFE0D16AE931B73C59D7E0C089C0"));
            check("md4 abc", MsChapV2.hex(Md4.digest("abc".getBytes())).equals("A448017AAF21D8525FC10AE87AA6729D"));
            byte[] big = new byte[1000]; Arrays.fill(big, (byte) 'a');
            check("md4 1000a", MsChapV2.hex(Md4.digest(big)).length() == 32);
            byte[] ac = hex("5B5D7C7D7B3F2F3E3C2C602132262628"), pc = hex("21402324255E262A28295F2B3A337C7E");
            check("nt password hash", MsChapV2.hex(MsChapV2.ntPasswordHash("clientPass")).equals("44EBBA8D5312B8D611474411F56989AE"));
            check("challenge hash", MsChapV2.hex(MsChapV2.challengeHash(pc, ac, "User")).equals("D02E4386BCE91226"));
            byte[] ntr = MsChapV2.ntResponse(ac, pc, "User", "clientPass");
            check("nt response", MsChapV2.hex(ntr).equals("82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF"));
            check("authenticator response", MsChapV2.authenticatorResponse("clientPass", ntr, pc, ac, "User")
                    .equals("S=407A5589115FD0D6209F510FE9C04566932CDA56"));
            byte[] mk = MsChapV2.masterKey("clientPass", ntr);
            check("mppe master key", MsChapV2.hex(mk).equals("FDECE3717A8C838CB388E527AE3CDD31"));
            check("mppe server send key (= client receive)", MsChapV2.hex(MsChapV2.clientKey(mk, false)).equals("8B7CDC149B993A1BA118CB153F56DCCB"));
        } else {
            int port = Integer.parseInt(args[1]);
            LinkedBlockingQueue<byte[]> got = new LinkedBlockingQueue<>();
            final String[] closed = {null};
            SstpClient c = new SstpClient("127.0.0.1", port, "vpn", "vpn");
            SstpClient.Result r = c.connect(s -> true, new SstpClient.Listener() {
                public void onIpPacket(byte[] b, int o, int l) { got.add(Arrays.copyOfRange(b, o, o + l)); }
                public void onClosed(String reason) { closed[0] = reason; }
            }, 3000, 15000);
            check("assigned ip", Arrays.equals(r.address, new byte[]{10, 0, 0, 5}));
            check("dns1", Arrays.equals(r.dns1, new byte[]{8, 8, 8, 8}));
            check("dns2", Arrays.equals(r.dns2, new byte[]{8, 8, 4, 4}));
            check("auth " + args[0], r.auth.replace("-","").equalsIgnoreCase(args[0].equals("md5") ? "CHAPMD5" : args[0]));
            check("mtu", r.mtu == 1400);
            for (int i = 0; i < 3; i++) {
                byte[] ip = new byte[60];
                ip[0] = 0x45; ip[3] = 60; ip[9] = 17;
                ip[12] = 10; ip[15] = 5; ip[16] = 1; ip[17] = 2; ip[18] = 3; ip[19] = 4;
                ip[40] = (byte) i;
                c.sendIp(ip, 0, ip.length);
                byte[] back = got.poll(3, TimeUnit.SECONDS);
                check("ip echo " + i, back != null && back.length == 60 && back[16] == 10 && back[12] == 1 && back[40] == i);
            }
            Thread.sleep(500);
            check("tick alive", c.tick());
            c.close();
            check("closed", !c.isAlive());
        }
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
