package ru.prodamus.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class UtteranceDetector {
    private static final Logger log = LoggerFactory.getLogger(UtteranceDetector.class);
    private static final int BYTES_PER_SECOND = 32_000;
    private static final int PRE_ROLL_BYTES = 6_400;
    private static final int MIN_UTTERANCE_BYTES = 4_800;
    private static final int MAX_UTTERANCE_BYTES = 960_000;

    private final SpeakerRole role;
    private final int threshold;
    private final int silenceBytesRequired;
    private final int segmentBytesRequired;
    private final Consumer<SpeechSegment> utteranceConsumer;
    private final Deque<byte[]> preRoll = new ArrayDeque<>();
    private final ByteArrayOutputStream utterance = new ByteArrayOutputStream();
    private int preRollBytes;
    private int silenceBytes;
    private int segmentVoiceBytes;
    private boolean speaking;
    private boolean activeSegmentEmitted;
    private double peakRms;
    private long utteranceSequence;
    private long currentUtteranceId;

    public UtteranceDetector(SpeakerRole role, int threshold, int silenceMillis,
                             BiConsumer<SpeakerRole, byte[]> utteranceConsumer) {
        this(role, threshold, silenceMillis, 0,
                segment -> utteranceConsumer.accept(segment.role(), segment.audio()));
    }

    public UtteranceDetector(SpeakerRole role, int threshold, int silenceMillis, int segmentMillis,
                             BiConsumer<SpeakerRole, byte[]> utteranceConsumer) {
        this(role, threshold, silenceMillis, segmentMillis,
                segment -> utteranceConsumer.accept(segment.role(), segment.audio()));
    }

    public UtteranceDetector(SpeakerRole role, int threshold, int silenceMillis, int segmentMillis,
                             Consumer<SpeechSegment> utteranceConsumer) {
        this.role = role;
        this.threshold = threshold;
        this.silenceBytesRequired = Math.max(9_600, BYTES_PER_SECOND * silenceMillis / 1_000);
        this.segmentBytesRequired = segmentMillis <= 0 ? 0
                : Math.max(MIN_UTTERANCE_BYTES, BYTES_PER_SECOND * segmentMillis / 1_000);
        this.utteranceConsumer = utteranceConsumer;
        log.info("VAD initialized: role={}, threshold={}, silenceMs={}, segmentMs={}, minUtteranceMs={}, maxUtteranceMs={}",
                role, threshold, silenceMillis, segmentMillis,
                MIN_UTTERANCE_BYTES * 1000 / BYTES_PER_SECOND,
                MAX_UTTERANCE_BYTES * 1000 / BYTES_PER_SECOND);
    }

    public synchronized void accept(byte[] pcm) {
        double currentRms = rms(pcm);
        boolean voice = currentRms >= threshold;
        if (!speaking) {
            addPreRoll(pcm);
            if (voice) {
                speaking = true;
                currentUtteranceId = ++utteranceSequence;
                activeSegmentEmitted = false;
                peakRms = currentRms;
                log.debug("VAD speech started: role={}, rms={}, preRollBytes={}", role,
                        Math.round(currentRms), preRollBytes);
                preRoll.forEach(utterance::writeBytes);
                segmentVoiceBytes = pcm.length;
                preRoll.clear();
                preRollBytes = 0;
                silenceBytes = 0;
            }
            return;
        }

        utterance.writeBytes(pcm);
        if (voice) segmentVoiceBytes += pcm.length;
        peakRms = Math.max(peakRms, currentRms);
        silenceBytes = voice ? 0 : silenceBytes + pcm.length;
        if (silenceBytes >= silenceBytesRequired) {
            finish();
        } else if (segmentBytesRequired > 0 && utterance.size() >= segmentBytesRequired) {
            emitSegment();
        } else if (utterance.size() >= MAX_UTTERANCE_BYTES) {
            finish();
        }
    }

    public synchronized void flush() {
        log.debug("VAD flush: role={}, speaking={}, bufferedBytes={}", role, speaking, utterance.size());
        if (speaking) finish();
        preRoll.clear();
        preRollBytes = 0;
    }

    private void finish() {
        byte[] data = utterance.toByteArray();
        utterance.reset();
        speaking = false;
        silenceBytes = 0;
        long durationMs = data.length * 1000L / BYTES_PER_SECOND;
        if ((segmentVoiceBytes > 0 && data.length >= MIN_UTTERANCE_BYTES) || activeSegmentEmitted) {
            log.info("VAD utterance complete: role={}, bytes={}, durationMs={}, peakRms={}",
                    role, data.length, durationMs, Math.round(peakRms));
            utteranceConsumer.accept(new SpeechSegment(role, currentUtteranceId, true, data));
        } else {
            log.debug("VAD utterance ignored as too short: role={}, bytes={}, durationMs={}",
                    role, data.length, durationMs);
        }
        segmentVoiceBytes = 0;
        activeSegmentEmitted = false;
        peakRms = 0;
        currentUtteranceId = 0;
    }

    private void emitSegment() {
        byte[] data = utterance.toByteArray();
        utterance.reset();
        if (segmentVoiceBytes > 0 && data.length >= MIN_UTTERANCE_BYTES) {
            log.info("VAD active-listening segment: role={}, bytes={}, durationMs={}",
                    role, data.length, data.length * 1000L / BYTES_PER_SECOND);
            utteranceConsumer.accept(new SpeechSegment(role, currentUtteranceId, false, data));
            activeSegmentEmitted = true;
        }
        segmentVoiceBytes = 0;
        peakRms = 0;
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

    public record SpeechSegment(SpeakerRole role, long utteranceId, boolean finalSegment, byte[] audio) {
        public SpeechSegment {
            audio = audio == null ? new byte[0] : audio;
        }
    }
}
