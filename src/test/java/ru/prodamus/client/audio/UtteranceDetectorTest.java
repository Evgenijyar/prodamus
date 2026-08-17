package ru.prodamus.client.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UtteranceDetectorTest {
    @Test
    void streamsUtteranceWithCorrectRoleBeforeSilenceCompletes() {
        List<String> events = new ArrayList<>();
        List<byte[]> chunks = new ArrayList<>();
        UtteranceDetector detector = new UtteranceDetector(SpeakerRole.CUSTOMER, 500, 300,
                new UtteranceDetector.StreamListener() {
                    @Override public void onStarted(SpeakerRole role, byte[] initialAudio) {
                        events.add("start:" + role);
                        chunks.add(initialAudio);
                    }
                    @Override public void onAudio(SpeakerRole role, byte[] audio) {
                        events.add("audio:" + role);
                        chunks.add(audio);
                    }
                    @Override public void onEnded(SpeakerRole role) {
                        events.add("end:" + role);
                    }
                });

        detector.accept(pcm(0, 3_200));
        detector.accept(pcm(4_000, 6_400));
        assertThat(events).containsExactly("start:CUSTOMER");

        detector.accept(pcm(4_000, 6_400));
        assertThat(events).containsExactly("start:CUSTOMER", "audio:CUSTOMER");

        detector.accept(pcm(0, 9_600));

        assertThat(events).containsExactly(
                "start:CUSTOMER", "audio:CUSTOMER", "audio:CUSTOMER", "end:CUSTOMER");
        assertThat(chunks.stream().mapToInt(bytes -> bytes.length).sum()).isGreaterThanOrEqualTo(16_000);
    }

    @Test
    void ignoresSilence() {
        List<String> events = new ArrayList<>();
        UtteranceDetector detector = new UtteranceDetector(SpeakerRole.MANAGER, 500, 500,
                new UtteranceDetector.StreamListener() {
                    @Override public void onStarted(SpeakerRole role, byte[] initialAudio) { events.add("start"); }
                    @Override public void onAudio(SpeakerRole role, byte[] audio) { events.add("audio"); }
                    @Override public void onEnded(SpeakerRole role) { events.add("end"); }
                });
        detector.accept(pcm(0, 32_000));
        detector.flush();
        assertThat(events).isEmpty();
    }

    private byte[] pcm(int amplitude, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((i / 2 % 2 == 0) ? amplitude : -amplitude);
            bytes[i] = (byte) sample;
            bytes[i + 1] = (byte) (sample >> 8);
        }
        return bytes;
    }
}
