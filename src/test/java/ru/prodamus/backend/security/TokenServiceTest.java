package ru.prodamus.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {
    @Test
    void producesOpaqueRandomTokensAndStableHashes() {
        TokenService tokens = new TokenService();
        String first = tokens.randomToken();
        String second = tokens.randomToken();
        assertThat(first).isNotBlank().isNotEqualTo(second);
        assertThat(tokens.sha256(first)).hasSize(64).isEqualTo(tokens.sha256(first));
    }
}
