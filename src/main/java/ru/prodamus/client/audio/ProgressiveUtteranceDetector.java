package ru.prodamus.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Customer VAD that emits the first words quickly and then emits continuation
 * fragments without losing the boundary of the original utterance.
 */
public final class ProgressiveUtteranceDetector {
    private static final Logger log = LoggerFactory.getLogger(ProgressiveUtteranceDetector.class);
    private static final int BYTES_PER_SECOND = 32_000;
    private static final int PRE_ROLL_BYTES = 6_400;
    private static final int MIN_SEGMENT_BYTES = 4_800;
    /**
     * Keep a short, never-yet-sent audio tail behind every progressive update.
     * This guarantees that the real end-of-utterance signal always contains
     * audio and can never become activityStart/activityEnd with an empty body.
     */
    private static final int FINAL_TAIL_BYTES = 3_840; // 120 ms, PCM16 mono 16 kHz

    private final SpeakerRole role;
    private final int threshold;
    private final int silenceBytesRequired;
    private final int firstSegmentBytes;
    private final int continuationSegmentBytes;
    private final Consumer<SpeechSegment> consumer;
    private final Deque<byte[]> preRoll = new ArrayDeque<>();
    private final ByteArrayOutputStream segment = new ByteArrayOutputStream();

    private int preRollBytes;
    private int silenceBytes;
    private int segmentVoiceBytes;
    private long utteranceBytes;
    private int segmentIndex;
    private long utteranceSequence;
    private long currentUtteranceId;
    private boolean speaking;

    public ProgressiveUtteranceDetector(SpeakerRole role, int threshold, int silenceMillis,
                                        int firstSegmentMillis, int continuationSegmentMillis,
                                        Consumer<SpeechSegment> consumer) {
        this.role = role;
        this.threshold = threshold;
        this.silenceBytesRequired = Math.max(9_600, BYTES_PER_SECOND * silenceMillis / 1_000);
        this.firstSegmentBytes = Math.max(MIN_SEGMENT_BYTES,
                BYTES_PER_SECOND * firstSegmentMillis / 1_000);
        this.continuationSegmentBytes = Math.max(MIN_SEGMENT_BYTES,
                BYTES_PER_SECOND * continuationSegmentMillis / 1_000);
        this.consumer = consumer;
        log.info("Progressive VAD initialized: role={}, firstMs={}, continuationMs={}, silenceMs={}",
                role, firstSegmentMillis, continuationSegmentMillis, silenceMillis);
    }

    public synchronized void accept(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        boolean voice = rms(pcm) >= threshold;
        if (!speaking) {
            addPreRoll(pcm);
            if (!voice) return;
            speaking = true;
            currentUtteranceId = ++utteranceSequence;
            segmentIndex = 0;
            utteranceBytes = 0;
            silenceBytes = 0;
            segmentVoiceBytes = pcm.length;
            preRoll.forEach(segment::writeBytes);
            utteranceBytes = segment.size();
            preRoll.clear();
            preRollBytes = 0;
            emitIfThresholdReached();
            return;
        }

        segment.writeBytes(pcm);
        utteranceBytes += pcm.length;
        if (voice) segmentVoiceBytes += pcm.length;
        silenceBytes = voice ? 0 : silenceBytes + pcm.length;
        if (silenceBytes >= silenceBytesRequired) {
            finish();
        } else {
            emitIfThresholdReached();
        }
    }

    public synchronized void flush() {
        if (speaking) finish();
        preRoll.clear();
        preRollBytes = 0;
    }

    private void emitIfThresholdReached() {
        int target = segmentIndex == 0 ? firstSegmentBytes : continuationSegmentBytes;
        if (segment.size() >= target + FINAL_TAIL_BYTES) emitProgressive();
    }

    private void finish() {
        if ((segmentVoiceBytes > 0 || segmentIndex > 0) && segment.size() >= MIN_SEGMENT_BYTES) {
            emit(true);
        }
        segment.reset();
        speaking = false;
        silenceBytes = 0;
        segmentVoiceBytes = 0;
        utteranceBytes = 0;
        segmentIndex = 0;
    }

    private void emitProgressive() {
        byte[] buffered = segment.toByteArray();
        int emitLength = Math.max(MIN_SEGMENT_BYTES, buffered.length - FINAL_TAIL_BYTES);
        if ((emitLength & 1) != 0) emitLength--;
        byte[] audio = java.util.Arrays.copyOfRange(buffered, 0, emitLength);
        byte[] tail = java.util.Arrays.copyOfRange(buffered, emitLength, buffered.length);
        segment.reset();
        segment.writeBytes(tail);
        log.info("Progressive VAD segment: role={}, utterance={}, index={}, final=false, durationMs={}, heldBackMs={}",
                role, currentUtteranceId, segmentIndex,
                audio.length * 1000L / BYTES_PER_SECOND,
                tail.length * 1000L / BYTES_PER_SECOND);
        consumer.accept(new SpeechSegment(role, currentUtteranceId, segmentIndex, false,
                utteranceBytes, audio));
        segmentIndex++;
        // The retained bytes came from the active speech window. Preserve that
        // fact so a silence-only ending still emits a valid final audio turn.
        segmentVoiceBytes = segmentVoiceBytes > 0 ? Math.min(segmentVoiceBytes, tail.length) : 0;
    }

    private void emit(boolean finalSegment) {
        byte[] audio = segment.toByteArray();
        segment.reset();
        log.info("Progressive VAD segment: role={}, utterance={}, index={}, final={}, durationMs={}",
                role, currentUtteranceId, segmentIndex, finalSegment,
                audio.length * 1000L / BYTES_PER_SECOND);
        consumer.accept(new SpeechSegment(role, currentUtteranceId, segmentIndex, finalSegment,
                utteranceBytes, audio));
        segmentIndex++;
        segmentVoiceBytes = 0;
    }

    private void addPreRoll(byte[] pcm) {
        preRoll.addLast(pcm.clone());
        preRollBytes += pcm.length;
        while (preRollBytes > PRE_ROLL_BYTES && !preRoll.isEmpty()) {
            preRollBytes -= preRoll.removeFirst().length;
        }
    }

    private double rms(byte[] pcm) {
        if (pcm.length < 2) return 0;
        double sum = 0;
        int samples = pcm.length / 2;
        for (int i = 0; i < samples; i++) {
            int sample = (pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8);
            sum += (double) sample * sample;
        }
        return Math.sqrt(sum / samples);
    }

    public record SpeechSegment(SpeakerRole role, long utteranceId, int segmentIndex,
                                boolean finalSegment, long cumulativeAudioBytes, byte[] audio) {
        public boolean firstSegment() { return segmentIndex == 0; }
    }
}
