package ru.prodamus.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

public final class UtteranceDetector {
    private static final Logger log = LoggerFactory.getLogger(UtteranceDetector.class);
    private static final int BYTES_PER_SECOND = 32_000;
    private static final int PRE_ROLL_BYTES = 6_400;
    private static final int MIN_UTTERANCE_BYTES = 4_800;
    private static final int MAX_UTTERANCE_BYTES = 960_000;

    private final SpeakerRole role;
    private final int threshold;
    private final int silenceBytesRequired;
    private final StreamListener streamListener;
    private final Deque<byte[]> preRoll = new ArrayDeque<>();
    private int preRollBytes;
    private int utteranceBytes;
    private int silenceBytes;
    private boolean speaking;
    private double peakRms;

    public UtteranceDetector(SpeakerRole role, int threshold, int silenceMillis,
                             StreamListener streamListener) {
        this.role = role;
        this.threshold = threshold;
        this.silenceBytesRequired = Math.max(9_600, BYTES_PER_SECOND * silenceMillis / 1_000);
        this.streamListener = streamListener;
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
                byte[] initialAudio = drainPreRoll();
                utteranceBytes = initialAudio.length;
                streamListener.onStarted(role, initialAudio);
                preRoll.clear();
                preRollBytes = 0;
                silenceBytes = 0;
            }
            return;
        }

        byte[] audio = pcm.clone();
        utteranceBytes += audio.length;
        streamListener.onAudio(role, audio);
        peakRms = Math.max(peakRms, currentRms);
        silenceBytes = voice ? 0 : silenceBytes + pcm.length;
        if (silenceBytes >= silenceBytesRequired || utteranceBytes >= MAX_UTTERANCE_BYTES) {
            finish();
        }
    }

    public synchronized void flush() {
        log.debug("VAD flush: role={}, speaking={}, streamedBytes={}", role, speaking, utteranceBytes);
        if (speaking) finish();
        preRoll.clear();
        preRollBytes = 0;
    }

    private void finish() {
        int dataLength = utteranceBytes;
        utteranceBytes = 0;
        speaking = false;
        silenceBytes = 0;
        long durationMs = dataLength * 1000L / BYTES_PER_SECOND;
        streamListener.onEnded(role);
        if (dataLength >= MIN_UTTERANCE_BYTES) {
            log.info("VAD streamed utterance complete: role={}, bytes={}, durationMs={}, peakRms={}",
                    role, dataLength, durationMs, Math.round(peakRms));
        } else {
            log.debug("VAD streamed short activity: role={}, bytes={}, durationMs={}", role, dataLength, durationMs);
        }
        peakRms = 0;
    }

    private byte[] drainPreRoll() {
        ByteArrayOutputStream initial = new ByteArrayOutputStream(preRollBytes);
        preRoll.forEach(initial::writeBytes);
        return initial.toByteArray();
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

    public interface StreamListener {
        void onStarted(SpeakerRole role, byte[] initialAudio);
        void onAudio(SpeakerRole role, byte[] audio);
        void onEnded(SpeakerRole role);
    }
}
