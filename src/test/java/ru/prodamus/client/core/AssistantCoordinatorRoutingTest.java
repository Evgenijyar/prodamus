package ru.prodamus.client.core;

import org.junit.jupiter.api.Test;
import ru.prodamus.client.audio.ProgressiveUtteranceDetector.SpeechSegment;
import ru.prodamus.client.audio.SpeakerRole;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantCoordinatorRoutingTest {
    @Test
    void coalescesQueuedActiveListeningAudioWithoutLosingFinalOrForecast() {
        SpeechSegment early = new SpeechSegment(SpeakerRole.CUSTOMER, 9L, 0,
                false, 3, new byte[]{1, 2, 3});
        SpeechSegment finalPart = new SpeechSegment(SpeakerRole.CUSTOMER, 9L, 1,
                true, 5, new byte[]{4, 5});
        AssistantCoordinator.PendingCustomerTurn pending =
                new AssistantCoordinator.PendingCustomerTurn(99L, early, "", true);

        AssistantCoordinator.PendingCustomerTurn merged = pending.merge(finalPart, "valid forecast");

        assertThat(merged.displayUtteranceId()).isEqualTo(99L);
        assertThat(merged.containsFirstSegment()).isTrue();
        assertThat(merged.segment().finalSegment()).isTrue();
        assertThat(merged.segment().segmentIndex()).isEqualTo(1);
        assertThat(merged.segment().cumulativeAudioBytes()).isEqualTo(5);
        assertThat(merged.segment().audio()).containsExactly(1, 2, 3, 4, 5);
        assertThat(merged.forecast()).isEqualTo("valid forecast");
    }
}
