package backend.backend.configuration;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
@Component
@RequiredArgsConstructor
public class CryptoUtils {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;

    private static final int GCM_IV_BYTES = 12;

    private final SecretKeyProperties secretKeyProperties;
    private static String KEY;
    private static String FIXED;

    @PostConstruct
    public void init() {

        KEY = secretKeyProperties.getKey();
        FIXED = secretKeyProperties.getFixed();
    }

    private static byte[] ivBytes() {
        byte[] raw = FIXED.getBytes();
        if (raw.length == GCM_IV_BYTES) return raw;
        byte[] iv = new byte[GCM_IV_BYTES];
        System.arraycopy(raw, 0, iv, 0, Math.min(raw.length, GCM_IV_BYTES));
        return iv;
    }

    public static String encrypt(String value) {
        if (KEY == null) throw new RuntimeException("Encryption Key not initialized!");
        try {
            Cipher cipher = Cipher.getInstance(ALGO);
            byte[] iv = ivBytes();
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes());

            byte[] combined = new byte[GCM_IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_BYTES, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static String decrypt(String encryptedValue) {
        if (KEY == null) throw new RuntimeException("Encryption Key not initialized!");
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedValue);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes(), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BIT, combined, 0, GCM_IV_BYTES));

            byte[] plaintext = cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES);
            return new String(plaintext);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}