package com.ltplatform.security.crypto;

import com.ltplatform.config.LtPlatformProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecretBox {
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(LtPlatformProperties props) {
        byte[] raw = props.getMasterKey().getBytes(StandardCharsets.UTF_8);
        this.key = Arrays.copyOf(raw, 32);
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return buffer.array();
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] encryptString(String value) {
        return encrypt(value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] decrypt(byte[] payload) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public String decryptString(byte[] payload) {
        return new String(decrypt(payload), StandardCharsets.UTF_8);
    }
}
