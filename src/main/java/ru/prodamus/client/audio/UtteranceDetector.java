package ru.prodamus.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BiConsumer;

public final class UtteranceDetector {
    private static final Logger log = LoggerFactory.getLogger(UtteranceDetector.class);
    private static final int BYTES_PER_SECOND = 32_000;
    private static final int PRE_ROLL_BYTES = 6_400;
    private static final int MIN_UTTERANCE_BYTES = 4_800;
    private static final int MAX_UTTERANCE_BYTES = 960_000;

    private final SpeakerRole role;
    private final int threshold;
    private final int silenceBytesRequired;
    private final BiConsumer<SpeakerRole, byte[]> utteranceConsumer;
    private final Deque<byte[]> preRoll = new ArrayDeque<>();
    private final ByteArrayOutputStream utterance = new ByteArrayOutputStream();
    private int preRollBytes;
    private int silenceBytes;
    private boolean speaking;
    private double peakRms;

    public UtteranceDetector(SpeakerRole role, int threshold, int silenceMillis,
                             BiConsumer<SpeakerRole, byte[]> utteranceConsumer) {
        this.role = role;
        this.threshold = threshold;
        this.silenceBytesRequired = Math.max(9_600, BYTES_PER_SECOND * silenceMillis / 1_000);
        this.utteranceConsumer = utteranceConsumer;
        log.info("VAD initialized: role={}, threshold={}, silenceMs={}, minUtteranceMs={}, maxUtteranceMs={}",
                role, threshold, silenceMillis, MIN_UTTERANCE_BYTES * 1000 / BYTES_PER_SECOND,
                MAX_UTTERANCE_BYTES * 1000 / BYTES_PER_SECOND);
    }

    public synchronized void accept(byte[] pcm) {
        double currentRms = rms(pcm);
        boolean voice = currentRms >= threshold;
        if (!speaking) {
            addPreRoll(pcm);
            if (voice) {
                speaking = true;
                peakRms = currentRms;
                log.debug("VAD speech started: role={}, rms={}, preRollBytes={}", role,
                        Math.round(currentRms), preRollBytes);
                preRoll.forEach(utterance::writeBytes);
                preRoll.clear();
                preRollBytes = 0;
                silenceBytes = 0;
            }
            return;
        }

        utterance.writeBytes(pcm);
        peakRms = Math.max(peakRms, currentRms);
        silenceBytes = voice ? 0 : silenceBytes + pcm.length;
        if (silenceBytes >= silenceBytesRequired || utterance.size() >= MAX_UTTERANCE_BYTES) finish();
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
        if (data.length >= MIN_UTTERANCE_BYTES) {
            log.info("VAD utterance complete: role={}, bytes={}, durationMs={}, peakRms={}",
                    role, data.length, durationMs, Math.round(peakRms));
            utteranceConsumer.accept(role, data);
        } else {
            log.debug("VAD utterance ignored as too short: role={}, bytes={}, durationMs={}",
                    role, data.length, durationMs);
        }
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
}
