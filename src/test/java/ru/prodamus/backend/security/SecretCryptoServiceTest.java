package ru.prodamus.backend.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCryptoServiceTest {
    @Test
    void encryptsAndDecryptsApiKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        SecretCryptoService crypto = new SecretCryptoService(Base64.getEncoder().encodeToString(key));
        String encrypted = crypto.encrypt("AIza-example-secret");
        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("AIza-example-secret");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("AIza-example-secret");
    }
}
