package ru.prodamus.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsAudioServiceTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void enumeratesWindowsWasapiEndpoints() {
        WindowsAudioService service = new WindowsAudioService();
        assertThat(service.listDevices(false)).allSatisfy(device -> {
            assertThat(device.id()).isNotBlank();
            assertThat(device.name()).isNotBlank();
        });
        assertThat(service.listDevices(true)).allSatisfy(device -> {
            assertThat(device.id()).isNotBlank();
            assertThat(device.name()).isNotBlank();
        });
    }
}
