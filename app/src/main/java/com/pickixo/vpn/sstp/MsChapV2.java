package com.pickixo.vpn.sstp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** MS-CHAPv2 (RFC 2759) and the MPPE master keys it yields (RFC 3079). */
final class MsChapV2 {
    private MsChapV2() {}

    private static final byte[] MAGIC1 = "Magic server to client signing constant".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAGIC2 = "Pad to make it do more than one iteration".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_MAGIC1 = "This is the MPPE Master Key".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_MAGIC2 =
            "On the client side, this is the send key; on the server side, it is the receive key."
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_MAGIC3 =
            "On the client side, this is the receive key; on the server side, it is the send key."
                    .getBytes(StandardCharsets.US_ASCII);

    static byte[] ntPasswordHash(String password) {
        return Md4.digest(password.getBytes(StandardCharsets.UTF_16LE));
    }

    static byte[] challengeHash(byte[] peerChallenge, byte[] authChallenge, String user) throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(peerChallenge);
        sha.update(authChallenge);
        sha.update(user.getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(sha.digest(), 8);
    }

    static byte[] ntResponse(byte[] authChallenge, byte[] peerChallenge, String user, String password)
            throws GeneralSecurityException {
        byte[] challenge = challengeHash(peerChallenge, authChallenge, user);
        byte[] zHash = Arrays.copyOf(ntPasswordHash(password), 21);
        byte[] out = new byte[24];
        for (int i = 0; i < 3; i++) {
            byte[] block = desEncrypt(Arrays.copyOfRange(zHash, 7 * i, 7 * i + 7), challenge);
            System.arraycopy(block, 0, out, 8 * i, 8);
        }
        return out;
    }

    /** The "S=..." string the server must send back; proves the server also knows the password. */
    static String authenticatorResponse(String password, byte[] ntResponse, byte[] peerChallenge,
                                        byte[] authChallenge, String user) throws GeneralSecurityException {
        byte[] hashHash = Md4.digest(ntPasswordHash(password));
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(hashHash);
        sha.update(ntResponse);
        sha.update(MAGIC1);
        byte[] digest = sha.digest();
        sha.update(digest);
        sha.update(challengeHash(peerChallenge, authChallenge, user));
        sha.update(MAGIC2);
        return "S=" + hex(sha.digest());
    }

    static byte[] masterKey(String password, byte[] ntResponse) throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(Md4.digest(ntPasswordHash(password)));
        sha.update(ntResponse);
        sha.update(KEY_MAGIC1);
        return Arrays.copyOf(sha.digest(), 16);
    }

    /** RFC 3079 GetAsymmetricStartKey, client side. */
    static byte[] clientKey(byte[] masterKey, boolean send) throws GeneralSecurityException {
        byte[] pad1 = new byte[40];
        byte[] pad2 = new byte[40];
        Arrays.fill(pad2, (byte) 0xF2);
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(masterKey);
        sha.update(pad1);
        sha.update(send ? KEY_MAGIC2 : KEY_MAGIC3);
        sha.update(pad2);
        return Arrays.copyOf(sha.digest(), 16);
    }

    /**
     * SSTP Higher-Layer Authentication Key for MS-CHAPv2 (MS-SSTP 3.2.5.2.3):
     * the server's MasterSendKey || MasterReceiveKey, i.e. the client's receive key then send key.
     */
    static byte[] hlak(String password, byte[] ntResponse) throws GeneralSecurityException {
        byte[] mk = masterKey(password, ntResponse);
        byte[] out = new byte[32];
        System.arraycopy(clientKey(mk, false), 0, out, 0, 16);
        System.arraycopy(clientKey(mk, true), 0, out, 16, 16);
        return out;
    }

    private static byte[] desEncrypt(byte[] key7, byte[] data) throws GeneralSecurityException {
        byte[] k = new byte[8];
        k[0] = (byte) ((key7[0] & 0xff) >> 1);
        k[1] = (byte) (((key7[0] & 0x01) << 6) | ((key7[1] & 0xff) >> 2));
        k[2] = (byte) (((key7[1] & 0x03) << 5) | ((key7[2] & 0xff) >> 3));
        k[3] = (byte) (((key7[2] & 0x07) << 4) | ((key7[3] & 0xff) >> 4));
        k[4] = (byte) (((key7[3] & 0x0f) << 3) | ((key7[4] & 0xff) >> 5));
        k[5] = (byte) (((key7[4] & 0x1f) << 2) | ((key7[5] & 0xff) >> 6));
        k[6] = (byte) (((key7[5] & 0x3f) << 1) | ((key7[6] & 0xff) >> 7));
        k[7] = (byte) (key7[6] & 0x7f);
        for (int i = 0; i < 8; i++) k[i] = (byte) (k[i] << 1);
        Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
        des.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "DES"));
        return des.doFinal(data);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xff));
        return sb.toString();
    }
}
