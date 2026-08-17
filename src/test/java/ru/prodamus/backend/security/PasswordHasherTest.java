package ru.prodamus.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {
    @Test
    void hashesWithRandomSaltAndVerifies() {
        PasswordHasher hasher = new PasswordHasher();
        String first = hasher.hash("very-secret");
        String second = hasher.hash("very-secret");
        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches("very-secret", first)).isTrue();
        assertThat(hasher.matches("wrong", first)).isFalse();
    }
}
