package ru.prodamus.client.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UtteranceDetectorTest {
    @Test
    void emitsOneUtteranceWithCorrectRoleAfterSilence() {
        List<SpeakerRole> roles = new ArrayList<>();
        List<byte[]> utterances = new ArrayList<>();
        UtteranceDetector detector = new UtteranceDetector(SpeakerRole.CUSTOMER, 500, 300,
                (role, bytes) -> {
                    roles.add(role);
                    utterances.add(bytes);
                });

        detector.accept(pcm(0, 3_200));
        detector.accept(pcm(4_000, 6_400));
        detector.accept(pcm(4_000, 6_400));
        detector.accept(pcm(0, 9_600));

        assertThat(roles).containsExactly(SpeakerRole.CUSTOMER);
        assertThat(utterances).hasSize(1);
        assertThat(utterances.getFirst().length).isGreaterThanOrEqualTo(16_000);
    }

    @Test
    void ignoresSilence() {
        List<byte[]> utterances = new ArrayList<>();
        UtteranceDetector detector = new UtteranceDetector(SpeakerRole.MANAGER, 500, 500,
                (role, bytes) -> utterances.add(bytes));
        detector.accept(pcm(0, 32_000));
        detector.flush();
        assertThat(utterances).isEmpty();
    }

    @Test
    void activeListeningSplitsContinuousCustomerSpeechWithoutLosingAudio() {
        List<SpeakerRole> roles = new ArrayList<>();
        List<byte[]> segments = new ArrayList<>();
        UtteranceDetector detector = new UtteranceDetector(SpeakerRole.CUSTOMER, 500, 300, 3_000,
                (role, bytes) -> {
                    roles.add(role);
                    segments.add(bytes);
                });

        detector.accept(pcm(0, 3_200));
        detector.accept(pcm(4_000, 6_400));
        for (int i = 0; i < 17; i++) detector.accept(pcm(4_000, 6_400));
        detector.accept(pcm(0, 9_600));

        assertThat(segments).hasSize(2);
        assertThat(roles).containsExactly(SpeakerRole.CUSTOMER, SpeakerRole.CUSTOMER);
        assertThat(segments.getFirst().length).isGreaterThanOrEqualTo(96_000);
        // Все байты с момента первого голосового блока сохранены; отбрасывается только
        // более старый предречевой нулевой блок, вышедший за 200-мс pre-roll.
        assertThat(segments.stream().mapToInt(bytes -> bytes.length).sum()).isEqualTo(124_800);
    }

    @Test
    void activeSegmentsKeepOneUtteranceIdUntilFinalSilence() {
        List<UtteranceDetector.SpeechSegment> segments = new ArrayList<>();
        UtteranceDetector detector = new UtteranceDetector(
                SpeakerRole.CUSTOMER, 500, 300, 1_000,
                (java.util.function.Consumer<UtteranceDetector.SpeechSegment>) segments::add);

        detector.accept(pcm(4_000, 16_000));
        detector.accept(pcm(4_000, 16_000));
        detector.accept(pcm(4_000, 16_000));
        detector.accept(pcm(4_000, 8_000));
        detector.accept(pcm(0, 9_600));

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
        assertThat(segments).extracting(UtteranceDetector.SpeechSegment::utteranceId).containsOnly(1L);
        assertThat(segments.getLast().finalSegment()).isTrue();
        assertThat(segments.subList(0, segments.size() - 1))
                .allMatch(segment -> !segment.finalSegment());
    }

    @Test
    void emitsFinalBoundaryWhenSpeechEndedExactlyAtActiveSegmentBoundary() {
        List<UtteranceDetector.SpeechSegment> segments = new ArrayList<>();
        UtteranceDetector detector = new UtteranceDetector(
                SpeakerRole.CUSTOMER, 500, 300, 1_000,
                (java.util.function.Consumer<UtteranceDetector.SpeechSegment>) segments::add);

        detector.accept(pcm(4_000, 6_400));
        detector.accept(pcm(4_000, 25_600)); // ровно 1 секунда: активный фрагмент отправлен
        detector.accept(pcm(0, 9_600));      // после него только финальная тишина

        assertThat(segments).hasSize(2);
        assertThat(segments.getFirst().finalSegment()).isFalse();
        assertThat(segments.getLast().finalSegment()).isTrue();
        assertThat(segments).extracting(UtteranceDetector.SpeechSegment::utteranceId).containsOnly(1L);
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
