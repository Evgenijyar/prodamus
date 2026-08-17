package ru.prodamus.client.config;

public record AppSettings(
        String microphoneDeviceId,
        String loopbackDeviceId,
        int vadThreshold,
        int silenceMillis,
        boolean excludeFromCapture,
        double overlayOpacity,
        boolean expandedPreferred,
        long lastRoleId
) {
    public static AppSettings defaults() {
        return new AppSettings("", "", 550, 700, true, 0.96, false, 0L);
    }
}
