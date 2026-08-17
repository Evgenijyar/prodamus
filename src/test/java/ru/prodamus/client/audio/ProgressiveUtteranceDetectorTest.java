package ru.prodamus.client.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressiveUtteranceDetectorTest {
    @Test
    void emitsEarlyFirstWordsThenRefinementsWithinOneUtterance() {
        List<ProgressiveUtteranceDetector.SpeechSegment> segments = new ArrayList<>();
        ProgressiveUtteranceDetector detector = new ProgressiveUtteranceDetector(
                SpeakerRole.CUSTOMER, 500, 300, 900, 2_000, segments::add);

        detector.accept(pcm(0, 3_200));
        for (int i = 0; i < 11; i++) detector.accept(pcm(4_000, 3_200));
        detector.accept(pcm(0, 9_600));

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
        assertThat(segments.getFirst().firstSegment()).isTrue();
        assertThat(segments.getFirst().finalSegment()).isFalse();
        assertThat(segments.getLast().finalSegment()).isTrue();
        assertThat(segments).extracting(ProgressiveUtteranceDetector.SpeechSegment::utteranceId)
                .containsOnly(1L);
        assertThat(segments.stream().mapToInt(value -> value.audio().length).sum()).isEqualTo(48_000);
        assertThat(segments.getLast().cumulativeAudioBytes()).isEqualTo(48_000);
    }

    @Test
    void emitsFinalBoundaryWhenOnlySilenceRemainsAfterEarlySegment() {
        List<ProgressiveUtteranceDetector.SpeechSegment> segments = new ArrayList<>();
        ProgressiveUtteranceDetector detector = new ProgressiveUtteranceDetector(
                SpeakerRole.CUSTOMER, 500, 300, 500, 2_000, segments::add);

        for (int i = 0; i < 5; i++) detector.accept(pcm(4_000, 3_200));
        detector.accept(pcm(0, 9_600));

        assertThat(segments.getFirst().finalSegment()).isFalse();
        assertThat(segments.getLast().finalSegment()).isTrue();
    }

    @Test
    void keepsLongSpeechAsOneUtteranceUntilRealSilence() {
        List<ProgressiveUtteranceDetector.SpeechSegment> segments = new ArrayList<>();
        ProgressiveUtteranceDetector detector = new ProgressiveUtteranceDetector(
                SpeakerRole.CUSTOMER, 500, 700, 900, 2_000, segments::add);

        for (int i = 0; i < 350; i++) detector.accept(pcm(4_000, 3_200));

        assertThat(segments).isNotEmpty();
        assertThat(segments).allMatch(segment -> segment.utteranceId() == 1L);
        assertThat(segments).noneMatch(ProgressiveUtteranceDetector.SpeechSegment::finalSegment);

        detector.accept(pcm(0, 22_400));

        assertThat(segments.getLast().finalSegment()).isTrue();
        assertThat(segments.getLast().cumulativeAudioBytes()).isEqualTo(1_142_400L);
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
