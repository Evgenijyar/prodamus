package ru.prodamus.client.gemini;

public interface GeminiEventListener {
    void onStatus(String status);
    void onSuggestion(String text, boolean complete);
    void onTranscript(String text);
    void onError(Throwable error);
}
