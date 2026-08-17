package ru.prodamus.client.gemini;

import ru.prodamus.client.audio.SpeakerRole;

public interface GeminiEventListener {
    void onStatus(String status);
    void onSuggestion(String text, boolean complete);
    void onTranscript(SpeakerRole role, String text, boolean complete);
    void onError(Throwable error);
}
