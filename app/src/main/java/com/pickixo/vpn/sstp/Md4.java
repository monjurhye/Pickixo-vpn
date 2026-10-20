package com.pickixo.vpn.sstp;

import java.util.Arrays;

/** MD4 (RFC 1320). Needed by MS-CHAPv2; Android does not ship an MD4 provider. */
final class Md4 {
    private Md4() {}

    private static final int[] R2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
    private static final int[] R3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};

    static byte[] digest(byte[] msg) {
        int n = msg.length;
        int padded = ((n + 8) / 64 + 1) * 64;
        byte[] b = Arrays.copyOf(msg, padded);
        b[n] = (byte) 0x80;
        long bits = (long) n * 8;
        for (int i = 0; i < 8; i++) b[padded - 8 + i] = (byte) (bits >>> (8 * i));

        int a = 0x67452301, bb = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
        int[] x = new int[16];
        for (int off = 0; off < padded; off += 64) {
            for (int i = 0; i < 16; i++) {
                int p = off + 4 * i;
                x[i] = (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
            }
            int sa = a, sb = bb, sc = c, sd = d;
            int[] s1 = {3, 7, 11, 19};
            for (int i = 0; i < 16; i++) {
                int t = Integer.rotateLeft(a + ((bb & c) | (~bb & d)) + x[i], s1[i & 3]);
                a = d; d = c; c = bb; bb = t;
            }
            int[] s2 = {3, 5, 9, 13};
            for (int i = 0; i < 16; i++) {
                int t = Integer.rotateLeft(a + ((bb & c) | (bb & d) | (c & d)) + x[R2[i]] + 0x5A827999, s2[i & 3]);
                a = d; d = c; c = bb; bb = t;
            }
            int[] s3 = {3, 9, 11, 15};
            for (int i = 0; i < 16; i++) {
                int t = Integer.rotateLeft(a + (bb ^ c ^ d) + x[R3[i]] + 0x6ED9EBA1, s3[i & 3]);
                a = d; d = c; c = bb; bb = t;
            }
            a += sa; bb += sb; c += sc; d += sd;
        }
        byte[] out = new byte[16];
        int[] v = {a, bb, c, d};
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) out[4 * i + j] = (byte) (v[i] >>> (8 * j));
        return out;
    }
}
