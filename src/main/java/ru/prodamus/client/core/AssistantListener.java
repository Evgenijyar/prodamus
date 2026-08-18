package ru.prodamus.client.core;

public interface AssistantListener {
    void onRunningChanged(boolean running);
    void onStatus(String status);
    default void onSuggestionStarted() { }
    void onSuggestion(String text, boolean complete);
    void onTranscript(String text);
    void onError(String message);
}
