package ru.prodamus.client.core;

import ru.prodamus.client.audio.SpeakerRole;

public interface AssistantListener {
    void onRunningChanged(boolean running);
    void onStatus(String status);
    void onSuggestion(String text, boolean complete);
    void onTranscript(SpeakerRole role, String text, boolean complete);
    void onError(String message);
}
