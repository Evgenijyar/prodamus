package ru.prodamus.client.gemini;

public interface GeminiEventListener {
    void onStatus(String status);
    default void onSuggestionStarted() { }
    void onSuggestion(String text, boolean complete);
    void onTranscript(String text);
    void onError(Throwable error);
}
