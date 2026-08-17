package ru.prodamus.client.config;

public record AppSettings(
        String microphoneDeviceId,
        String loopbackDeviceId,
        int vadThreshold,
        int silenceMillis,
        boolean excludeFromCapture,
        double overlayOpacity,
        boolean expandedPreferred,
        boolean activeListening,
        int activeListeningIntervalSeconds,
        long lastRoleId
) {
    public AppSettings {
        activeListeningIntervalSeconds = Math.max(2, Math.min(15, activeListeningIntervalSeconds));
    }

    public static AppSettings defaults() {
        return new AppSettings("", "", 550, 700, true, 0.96, false, false, 3, 0L);
    }
}
