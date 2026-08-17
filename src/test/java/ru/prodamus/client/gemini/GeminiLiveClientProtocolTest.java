package ru.prodamus.client.gemini;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLiveClientProtocolTest {
    @Test
    void usesGoogleRecommendedFortyMillisecondAudioChunks() {
        assertThat(GeminiLiveClient.AUDIO_CHUNK_BYTES).isEqualTo(1_280);
    }

    @Test
    void classifiesInvalidPayloadCloseAsNonRetryableProtocolFailure() {
        assertThat(GeminiLiveClient.classifyClose(1007))
                .isEqualTo(GeminiLiveClient.CloseDisposition.PROTOCOL);
        assertThat(GeminiLiveClient.classifyClose(1011))
                .isEqualTo(GeminiLiveClient.CloseDisposition.TRANSIENT);
    }

    @Test
    void mergesIncrementalAndCumulativeTranscriptionWithoutDuplication() {
        assertThat(GeminiLiveClient.mergeTranscript("Давайте", " зарегистрируемся."))
                .isEqualTo("Давайте зарегистрируемся.");
        assertThat(GeminiLiveClient.mergeTranscript("Давайте", "Давайте зарегистрируемся."))
                .isEqualTo("Давайте зарегистрируемся.");
        assertThat(GeminiLiveClient.mergeTranscript("Давайте зарегистрируемся.", "зарегистрируемся."))
                .isEqualTo("Давайте зарегистрируемся.");
    }
}
