package ru.prodamus.client.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLiveClientSuggestionBoundaryTest {

    @Test
    void opensOneCardPerModelResponseAndKeepsStreamingFragmentsInThatCard() {
        RecordingListener listener = new RecordingListener();
        GeminiLiveClient client = new GeminiLiveClient(new ObjectMapper(), listener);

        client.handleMessage(response("Первая "));
        client.handleMessage(response("подсказка"));
        client.handleMessage("{\"serverContent\":{\"turnComplete\":true}}");
        client.handleMessage(response("Вторая"));
        client.handleMessage("{\"serverContent\":{\"interrupted\":true}}");
        client.handleMessage(response("Третья"));

        assertThat(listener.starts).isEqualTo(3);
        assertThat(listener.updates).containsExactly(
                "Первая|false",
                "Первая подсказка|false",
                "Первая подсказка|true",
                "Вторая|false",
                "Третья|false"
        );
        assertThat(listener.errors).isEmpty();
    }

    private static String response(String text) {
        return "{\"serverContent\":{\"outputTranscription\":{\"text\":\"" + text + "\"}}}";
    }

    private static final class RecordingListener implements GeminiEventListener {
        private int starts;
        private final List<String> updates = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();

        @Override public void onStatus(String status) { }
        @Override public void onSuggestionStarted() { starts++; }
        @Override public void onSuggestion(String text, boolean complete) {
            updates.add(text + "|" + complete);
        }
        @Override public void onTranscript(String text) { }
        @Override public void onError(Throwable error) { errors.add(error); }
    }
}
