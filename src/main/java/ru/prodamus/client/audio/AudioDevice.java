package ru.prodamus.client.audio;

public record AudioDevice(String id, String name, boolean capture, boolean defaultDevice) {
    @Override
    public String toString() {
        return name + (defaultDevice ? "  (по умолчанию)" : "");
    }
}
