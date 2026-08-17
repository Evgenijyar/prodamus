package ru.prodamus.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SecretCryptoService {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCryptoService(@Value("${prodamus.crypto.master-key}") String masterKeyBase64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKeyBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("prodamus.crypto.master-key must be Base64", ex);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("prodamus.crypto.master-key must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) throw new IllegalArgumentException("Secret must not be blank");
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return "v1:" + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to encrypt secret", ex);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith("v1:")) {
            throw new IllegalArgumentException("Unsupported encrypted secret format");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext.substring(3));
            if (packed.length <= IV_BYTES) throw new IllegalArgumentException("Encrypted secret is truncated");
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[packed.length - IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            System.arraycopy(packed, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Unable to decrypt secret. Check PRODAMUS_MASTER_KEY.", ex);
        }
    }
}
