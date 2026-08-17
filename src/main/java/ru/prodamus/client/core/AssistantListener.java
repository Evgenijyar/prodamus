package ru.prodamus.client.core;

public interface AssistantListener {
    void onRunningChanged(boolean running);
    void onStatus(String status);
    void onSuggestion(SuggestionKind kind, String text, boolean complete);
    void onSuggestionBoundary();
    void onTranscript(String text);
    void onError(String message);
}
