package ru.prodamus.client.audio;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static ru.prodamus.client.audio.WasapiInterop.*;

@Service
public class WindowsAudioService {
    private static final Logger log = LoggerFactory.getLogger(WindowsAudioService.class);

    public List<AudioDevice> listDevices(boolean capture) {
        ensureWindows();
        log.info("Enumerating WASAPI {} endpoints", capture ? "capture" : "render/loopback");
        WinNT.HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
        boolean uninitialize = COMUtils.SUCCEEDED(initialized);
        try {
            MMDeviceEnumerator enumerator = createEnumerator();
            try {
                String defaultId = getDefaultId(enumerator, capture ? E_CAPTURE : E_RENDER);
                PointerByReference result = new PointerByReference();
                check(enumerator.enumAudioEndpoints(capture ? E_CAPTURE : E_RENDER,
                        DEVICE_STATE_ACTIVE, result), "EnumAudioEndpoints");
                MMDeviceCollection collection = new MMDeviceCollection(result.getValue());
                try {
                    IntByReference count = new IntByReference();
                    check(collection.getCount(count), "IMMDeviceCollection.GetCount");
                    List<AudioDevice> devices = new ArrayList<>();
                    for (int i = 0; i < count.getValue(); i++) {
                        PointerByReference devicePointer = new PointerByReference();
                        check(collection.item(i, devicePointer), "IMMDeviceCollection.Item");
                        MMDevice device = new MMDevice(devicePointer.getValue());
                        try {
                            String id = getId(device);
                            devices.add(new AudioDevice(id, getFriendlyName(device), capture,
                                    id.equalsIgnoreCase(defaultId)));
                        } finally {
                            device.Release();
                        }
                    }
                    log.info("WASAPI enumeration complete: type={}, count={}, devices={}",
                            capture ? "capture" : "render", devices.size(),
                            devices.stream().map(device -> device.name() + (device.defaultDevice() ? " [default]" : "")).toList());
                    return devices;
                } finally {
                    collection.Release();
                }
            } finally {
                enumerator.Release();
            }
        } finally {
            if (uninitialize) Ole32.INSTANCE.CoUninitialize();
        }
    }

    public WasapiCapture captureMicrophone(String deviceId, Consumer<byte[]> consumer,
                                           Consumer<Throwable> errorHandler) {
        log.debug("Creating microphone capture: deviceId={}", abbreviate(deviceId));
        return new WasapiCapture(E_CAPTURE, deviceId, false, consumer, errorHandler);
    }

    public WasapiCapture captureLoopback(String deviceId, Consumer<byte[]> consumer,
                                         Consumer<Throwable> errorHandler) {
        log.debug("Creating render loopback capture: deviceId={}", abbreviate(deviceId));
        return new WasapiCapture(E_RENDER, deviceId, true, consumer, errorHandler);
    }

    private MMDeviceEnumerator createEnumerator() {
        log.trace("Creating Windows MMDeviceEnumerator COM object");
        PointerByReference result = new PointerByReference();
        check(Ole32.INSTANCE.CoCreateInstance(CLSID_MM_DEVICE_ENUMERATOR, Pointer.NULL,
                WTypes.CLSCTX_ALL, IID_IMM_DEVICE_ENUMERATOR, result), "CoCreateInstance(MMDeviceEnumerator)");
        return new MMDeviceEnumerator(result.getValue());
    }

    private MMDevice findDevice(MMDeviceEnumerator enumerator, int flow, String requestedId) {
        if (requestedId == null || requestedId.isBlank()) {
            log.info("Using default WASAPI endpoint: flow={}", flow == E_CAPTURE ? "capture" : "render");
            PointerByReference result = new PointerByReference();
            check(enumerator.getDefaultAudioEndpoint(flow, 2, result), "GetDefaultAudioEndpoint");
            return new MMDevice(result.getValue());
        }
        PointerByReference collectionPointer = new PointerByReference();
        check(enumerator.enumAudioEndpoints(flow, DEVICE_STATE_ACTIVE, collectionPointer), "EnumAudioEndpoints");
        MMDeviceCollection collection = new MMDeviceCollection(collectionPointer.getValue());
        try {
            IntByReference count = new IntByReference();
            check(collection.getCount(count), "IMMDeviceCollection.GetCount");
            for (int i = 0; i < count.getValue(); i++) {
                PointerByReference devicePointer = new PointerByReference();
                check(collection.item(i, devicePointer), "IMMDeviceCollection.Item");
                MMDevice device = new MMDevice(devicePointer.getValue());
                if (requestedId.equalsIgnoreCase(getId(device))) {
                    log.info("Selected WASAPI endpoint found: flow={}, id={}, name={}",
                            flow == E_CAPTURE ? "capture" : "render", abbreviate(requestedId), getFriendlyName(device));
                    return device;
                }
                device.Release();
            }
        } finally {
            collection.Release();
        }
        throw new IllegalStateException("Выбранное аудиоустройство больше недоступно");
    }

    private String getDefaultId(MMDeviceEnumerator enumerator, int flow) {
        PointerByReference result = new PointerByReference();
        WinNT.HRESULT hr = enumerator.getDefaultAudioEndpoint(flow, 2, result);
        if (hr.intValue() < 0) return "";
        MMDevice device = new MMDevice(result.getValue());
        try {
            return getId(device);
        } finally {
            device.Release();
        }
    }

    private String getId(MMDevice device) {
        PointerByReference result = new PointerByReference();
        check(device.getId(result), "IMMDevice.GetId");
        Pointer pointer = result.getValue();
        try {
            return pointer.getWideString(0);
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(pointer);
        }
    }

    private String getFriendlyName(MMDevice device) {
        PointerByReference result = new PointerByReference();
        check(device.openPropertyStore(STGM_READ, result), "IMMDevice.OpenPropertyStore");
        PropertyStore store = new PropertyStore(result.getValue());
        try {
            PropVariant value = new PropVariant();
            check(store.getValue(PKEY_DEVICE_FRIENDLY_NAME, value), "IPropertyStore.GetValue");
            return value.stringValue();
        } finally {
            store.Release();
        }
    }

    private void ensureWindows() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new UnsupportedOperationException("Захват WASAPI поддерживается только в Windows");
        }
    }

    private String abbreviate(String id) {
        if (id == null || id.isBlank()) return "<default>";
        return id.length() <= 30 ? id : id.substring(0, 14) + "…" + id.substring(id.length() - 10);
    }

    public final class WasapiCapture implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean();
        private final int flow;
        private final String deviceId;
        private final boolean loopback;
        private final Consumer<byte[]> consumer;
        private final Consumer<Throwable> errorHandler;
        private Thread thread;
        private long totalBytes;
        private long packetCount;
        private long lastStatsNanos;

        private WasapiCapture(int flow, String deviceId, boolean loopback,
                              Consumer<byte[]> consumer, Consumer<Throwable> errorHandler) {
            this.flow = flow;
            this.deviceId = deviceId;
            this.loopback = loopback;
            this.consumer = consumer;
            this.errorHandler = errorHandler;
        }

        public synchronized void start() {
            if (!running.compareAndSet(false, true)) {
                log.warn("WASAPI capture start ignored; already running: type={}", loopback ? "loopback" : "microphone");
                return;
            }
            log.info("Starting WASAPI capture: type={}, flow={}, deviceId={}", loopback ? "loopback" : "microphone",
                    flow == E_CAPTURE ? "capture" : "render", abbreviate(deviceId));
            thread = Thread.ofPlatform().name(loopback ? "wasapi-loopback" : "wasapi-microphone")
                    .daemon(true).start(this::captureLoop);
        }

        private void captureLoop() {
            log.debug("WASAPI capture thread entered: type={}", loopback ? "loopback" : "microphone");
            WinNT.HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
            boolean uninitialize = COMUtils.SUCCEEDED(initialized);
            MMDeviceEnumerator enumerator = null;
            MMDevice device = null;
            AudioClient audioClient = null;
            AudioCaptureClient captureClient = null;
            WinNT.HANDLE event = null;
            try {
                lastStatsNanos = System.nanoTime();
                enumerator = createEnumerator();
                device = findDevice(enumerator, flow, deviceId);
                PointerByReference audioClientPointer = new PointerByReference();
                check(device.activate(IID_IAUDIO_CLIENT, WTypes.CLSCTX_ALL, audioClientPointer),
                        "IMMDevice.Activate(IAudioClient)");
                audioClient = new AudioClient(audioClientPointer.getValue());

                int flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                        AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
                if (loopback) flags |= AUDCLNT_STREAMFLAGS_LOOPBACK;
                log.debug("Initializing IAudioClient: type={}, sampleRate=16000, channels=1, bits=16, flags=0x{}",
                        loopback ? "loopback" : "microphone", Integer.toHexString(flags).toUpperCase());
                WaveFormatEx format = WaveFormatEx.pcm16Mono16k();
                check(audioClient.initialize(AUDCLNT_SHAREMODE_SHARED, flags, 10_000_000L, 0, format),
                        "IAudioClient.Initialize");

                event = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
                if (event == null) throw new IllegalStateException("Windows CreateEvent вернул null");
                check(audioClient.setEventHandle(event), "IAudioClient.SetEventHandle");

                PointerByReference capturePointer = new PointerByReference();
                check(audioClient.getService(IID_IAUDIO_CAPTURE_CLIENT, capturePointer),
                        "IAudioClient.GetService(IAudioCaptureClient)");
                captureClient = new AudioCaptureClient(capturePointer.getValue());
                check(audioClient.start(), "IAudioClient.Start");
                log.info("WASAPI capture started: type={}", loopback ? "loopback" : "microphone");

                while (running.get()) {
                    int waitResult = Kernel32.INSTANCE.WaitForSingleObject(event, 250);
                    if (waitResult == WinError.WAIT_TIMEOUT) {
                        consumer.accept(new byte[8_000]);
                    } else {
                        drainPackets(captureClient);
                    }
                }
                audioClient.stop();
            } catch (Throwable throwable) {
                if (running.get()) {
                    log.error("WASAPI capture failed: type={}, deviceId={}, bytes={}, packets={}",
                            loopback ? "loopback" : "microphone", abbreviate(deviceId), totalBytes, packetCount, throwable);
                    errorHandler.accept(throwable);
                } else {
                    log.debug("WASAPI capture ended during shutdown: type={}, reason={}",
                            loopback ? "loopback" : "microphone", throwable.toString());
                }
            } finally {
                running.set(false);
                if (captureClient != null) captureClient.Release();
                if (audioClient != null) audioClient.Release();
                if (device != null) device.Release();
                if (enumerator != null) enumerator.Release();
                if (event != null) Kernel32.INSTANCE.CloseHandle(event);
                if (uninitialize) Ole32.INSTANCE.CoUninitialize();
                log.info("WASAPI capture thread finished: type={}, bytes={}, packets={}",
                        loopback ? "loopback" : "microphone", totalBytes, packetCount);
            }
        }

        private void drainPackets(AudioCaptureClient client) {
            IntByReference nextFrames = new IntByReference();
            check(client.getNextPacketSize(nextFrames), "IAudioCaptureClient.GetNextPacketSize");
            while (nextFrames.getValue() > 0) {
                PointerByReference data = new PointerByReference();
                IntByReference frames = new IntByReference();
                IntByReference flags = new IntByReference();
                check(client.getBuffer(data, frames, flags, new LongByReference(), new LongByReference()),
                        "IAudioCaptureClient.GetBuffer");
                int byteCount = frames.getValue() * 2;
                byte[] bytes = (flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0
                        ? new byte[byteCount] : data.getValue().getByteArray(0, byteCount);
                check(client.releaseBuffer(frames.getValue()), "IAudioCaptureClient.ReleaseBuffer");
                if (bytes.length > 0) consumer.accept(bytes);
                totalBytes += bytes.length;
                packetCount++;
                long now = System.nanoTime();
                if (now - lastStatsNanos >= 5_000_000_000L) {
                    log.debug("WASAPI capture stats: type={}, bytes={}, packets={}",
                            loopback ? "loopback" : "microphone", totalBytes, packetCount);
                    lastStatsNanos = now;
                }
                check(client.getNextPacketSize(nextFrames), "IAudioCaptureClient.GetNextPacketSize");
            }
        }

        @Override
        public synchronized void close() {
            log.info("Stopping WASAPI capture: type={}, running={}, bytes={}, packets={}",
                    loopback ? "loopback" : "microphone", running.get(), totalBytes, packetCount);
            running.set(false);
            if (thread != null) thread.interrupt();
        }
    }
}
