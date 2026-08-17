package ru.prodamus.client.gemini;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLiveClientTest {
    @Test
    void mergesIncrementalAndCumulativeOutputWithoutDuplication() {
        assertThat(GeminiLiveClient.mergeTranscript("Скажи, что", " это не обязывает."))
                .isEqualTo("Скажи, что это не обязывает.");
        assertThat(GeminiLiveClient.mergeTranscript("Скажи, что", "Скажи, что это не обязывает."))
                .isEqualTo("Скажи, что это не обязывает.");
        assertThat(GeminiLiveClient.mergeTranscript("Скажи, что это не обязывает.", "не обязывает."))
                .isEqualTo("Скажи, что это не обязывает.");
    }
}
