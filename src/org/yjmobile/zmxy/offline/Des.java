package org.yjmobile.zmxy.offline;

import android.util.Base64;

import java.security.Key;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire codec for the game protocol, recovered from libgame.so.
 *
 *   YJNetWorkManager::newRequestObjWithActionAndJson ->
 *        yjDesEncrypt("43((zmIp", json, len, &out, &outlen, 0x120)
 *   YJNetWorkManager::requestFinished ->
 *        UNZIP_RESPONSE_IF_NECCESSARY(resp); yjDesDecrypt("43((zmIp", body, len, &out, &outlen, 0x120)
 *
 * mode 0x120 = bit 0x100 | bit 0x020, and yjDesDecrypt honours both:
 *   0x3bf552  ldr r1,[sp,#0x54]      ; mode
 *   0x3bf554  movs r5,#0x80 ; lsls r5,#1  ; 0x100
 *   0x3bf558  ands r5,r1
 *   0x3bf55c  beq  0x3bf59e          ; no flag -> raw ciphertext
 *   0x3bf56c  bl   base64_decode     ; flag 0x100 -> base64 text on the wire
 *   0x3bf600..0x3bf628               ; flag 0x020 -> strip PKCS#5 (zero-fills)
 *
 * => wire bytes are   base64( DES-ECB-PKCS5( JSON ) ).
 */
public final class Des {
    /**
     * 11.4.0 key, recovered in v11/ (handoff §2): the wire body is
     * "-=4399=-" + base64(DES-ECB/PKCS5(json)), key "1#(z1Rr>".
     * The 8.0 build used "43((zmIp"; using that key here made every 11.4
     * request undecodable, which surfaced as "网络传输数据出现错误".
     */
    private static final byte[] KEY = {0x31, 0x23, 0x28, 0x7a, 0x31, 0x52, 0x72, 0x3e};
    private static final byte[] PREFIX = {'-', '=', '4', '3', '9', '9', '=', '-'};

    private Des() {
    }

    private static Cipher cipher(int mode, byte[] key) throws Exception {
        Key k = new SecretKeySpec(key, "DES");
        Cipher c = Cipher.getInstance("DES/ECB/PKCS5Padding");
        c.init(mode, k);
        return c;
    }

    /** wire (base64 text, optional "-=4399=-" prefix) -> plaintext JSON */
    public static byte[] decrypt(byte[] wire) throws Exception {
        if (wire != null && wire.length > PREFIX.length) {
            boolean p = true;
            for (int i = 0; i < PREFIX.length; i++) {
                if (wire[i] != PREFIX[i]) {
                    p = false;
                    break;
                }
            }
            if (p) {
                byte[] rest = new byte[wire.length - PREFIX.length];
                System.arraycopy(wire, PREFIX.length, rest, 0, rest.length);
                wire = rest;
            }
        }
        byte[] ct;
        try {
            ct = Base64.decode(wire, Base64.DEFAULT);
        } catch (Throwable t) {
            ct = wire;
        }
        int n = (ct.length / 8) * 8;
        if (n == 0) {
            return new byte[0];
        }
        byte[] buf = ct;
        if (n != ct.length) {
            buf = new byte[n];
            System.arraycopy(ct, 0, buf, 0, n);
        }
        return cipher(Cipher.DECRYPT_MODE, KEY).doFinal(buf);
    }

    /** plaintext JSON -> wire (base64 text, single line) */
    public static byte[] encrypt(byte[] plain) throws Exception {
        byte[] ct = cipher(Cipher.ENCRYPT_MODE, KEY).doFinal(plain);
        return Base64.encodeToString(ct, Base64.NO_WRAP).getBytes("US-ASCII");
    }

    public static String utf8(byte[] b) {
        try {
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return new String(b);
        }
    }

    public static byte[] bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }
}

