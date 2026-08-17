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
        boolean dualSession,
        long lastRoleId
) {
    public AppSettings {
        activeListeningIntervalSeconds = Math.max(1, Math.min(5, activeListeningIntervalSeconds));
    }

    public static AppSettings defaults() {
        return new AppSettings("", "", 550, 700, true, 0.96, false, true, 2, true, 0L);
    }
}
