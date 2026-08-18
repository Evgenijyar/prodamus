package ru.prodamus.client.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.prodamus.client.audio.SpeakerRole;
import ru.prodamus.client.config.SettingsService;
import ru.prodamus.client.server.BackendClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real backend + Gemini smoke test; skipped during normal builds. */
class SalesHelperLiveSmokeTest {
    @Test
    void returnsSuggestionThroughProductionSalesHelperPipeline() throws Exception {
        String wavPath = System.getenv("PRODAMUS_LIVE_SMOKE_WAV");
        Assumptions.assumeTrue(wavPath != null && !wavPath.isBlank());

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SettingsService settings = new SettingsService();
        BackendClient backend = new BackendClient(mapper, settings, "https://prodamus.abs7.ru", "2.0.0");
        assertThat(backend.restoreRememberedSession()).isTrue();
        BackendClient.Bootstrap bootstrap = backend.bootstrap();
        assertThat(bootstrap.roles()).isNotEmpty();
        BackendClient.LiveSessionDescriptor descriptor = backend.startLiveSession(bootstrap.roles().getFirst().id());
        assertThat(descriptor.systemInstruction()).contains("незаметный ассистент менеджера по продажам");

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> suggestion = new AtomicReference<>("");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GeminiLiveClient client = new GeminiLiveClient(mapper, new GeminiEventListener() {
            @Override public void onStatus(String status) { }
            @Override public void onSuggestion(String text, boolean complete) {
                suggestion.set(text);
                if (complete) completed.countDown();
            }
            @Override public void onTranscript(String text) { }
            @Override public void onError(Throwable error) {
                failure.set(error);
                completed.countDown();
            }
        })) {
            client.connect(descriptor);
            client.sendUtterance(SpeakerRole.CUSTOMER, wavData(Files.readAllBytes(Path.of(wavPath))));
            assertThat(completed.await(45, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failure.get()).isNull();
        assertThat(suggestion.get()).isNotBlank().isNotEqualTo("—").isNotEqualTo("-");
    }

    private byte[] wavData(byte[] wav) {
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String id = new String(wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = header.getInt(offset + 4);
            int dataStart = offset + 8;
            if ("data".equals(id) && dataStart + size <= wav.length) {
                return java.util.Arrays.copyOfRange(wav, dataStart, dataStart + size);
            }
            offset = dataStart + size + (size & 1);
        }
        throw new IllegalArgumentException("WAV data chunk not found");
    }
}
