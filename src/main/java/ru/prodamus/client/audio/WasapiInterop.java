package ru.prodamus.client.audio;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class WasapiInterop {
    private static final Logger log = LoggerFactory.getLogger(WasapiInterop.class);
    static final Guid.CLSID CLSID_MM_DEVICE_ENUMERATOR = new Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    static final Guid.IID IID_IMM_DEVICE_ENUMERATOR = new Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    static final Guid.IID IID_IAUDIO_CLIENT = new Guid.IID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}");
    static final Guid.IID IID_IAUDIO_CAPTURE_CLIENT = new Guid.IID("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}");

    static final int E_RENDER = 0;
    static final int E_CAPTURE = 1;
    static final int E_ALL = 2;
    static final int DEVICE_STATE_ACTIVE = 1;
    static final int STGM_READ = 0;
    static final int AUDCLNT_SHAREMODE_SHARED = 0;
    static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    static final int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    static final int AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000;
    static final int AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000;
    static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
    static final short WAVE_FORMAT_PCM = 1;
    static final short VT_LPWSTR = 31;

    static final PropertyKey PKEY_DEVICE_FRIENDLY_NAME = new PropertyKey(
            new Guid.GUID("{A45C254E-DF1C-4EFD-8020-67D146A850E0}"), 14);

    private WasapiInterop() {
    }

    static WinNT.HRESULT hr(Object value) {
        return (WinNT.HRESULT) value;
    }

    static void check(WinNT.HRESULT result, String operation) {
        if (result.intValue() < 0) {
            log.error("WASAPI/COM call failed: operation={}, hresult=0x{}", operation,
                    Integer.toHexString(result.intValue()).toUpperCase());
            throw new IllegalStateException(operation + " завершилась с HRESULT 0x" +
                    Integer.toHexString(result.intValue()).toUpperCase());
        }
    }

    public static final class MMDeviceEnumerator extends Unknown {
        MMDeviceEnumerator(Pointer pointer) { super(pointer); }

        WinNT.HRESULT enumAudioEndpoints(int flow, int stateMask, PointerByReference devices) {
            return hr(_invokeNativeObject(3, new Object[]{getPointer(), flow, stateMask, devices}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT getDefaultAudioEndpoint(int flow, int role, PointerByReference device) {
            return hr(_invokeNativeObject(4, new Object[]{getPointer(), flow, role, device}, WinNT.HRESULT.class));
        }
    }

    public static final class MMDeviceCollection extends Unknown {
        MMDeviceCollection(Pointer pointer) { super(pointer); }

        WinNT.HRESULT getCount(IntByReference count) {
            return hr(_invokeNativeObject(3, new Object[]{getPointer(), count}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT item(int index, PointerByReference device) {
            return hr(_invokeNativeObject(4, new Object[]{getPointer(), index, device}, WinNT.HRESULT.class));
        }
    }

    public static final class MMDevice extends Unknown {
        MMDevice(Pointer pointer) { super(pointer); }

        WinNT.HRESULT activate(Guid.IID iid, int context, PointerByReference result) {
            return hr(_invokeNativeObject(3, new Object[]{getPointer(), iid, context, Pointer.NULL, result}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT openPropertyStore(int access, PointerByReference store) {
            return hr(_invokeNativeObject(4, new Object[]{getPointer(), access, store}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT getId(PointerByReference id) {
            return hr(_invokeNativeObject(5, new Object[]{getPointer(), id}, WinNT.HRESULT.class));
        }
    }

    public static final class PropertyStore extends Unknown {
        PropertyStore(Pointer pointer) { super(pointer); }

        WinNT.HRESULT getValue(PropertyKey key, PropVariant value) {
            return hr(_invokeNativeObject(5, new Object[]{getPointer(), key, value}, WinNT.HRESULT.class));
        }
    }

    public static final class AudioClient extends Unknown {
        AudioClient(Pointer pointer) { super(pointer); }

        WinNT.HRESULT initialize(int shareMode, int flags, long bufferDuration, long periodicity,
                                 WaveFormatEx format) {
            return hr(_invokeNativeObject(3, new Object[]{getPointer(), shareMode, flags, bufferDuration,
                    periodicity, format, Pointer.NULL}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT start() {
            return hr(_invokeNativeObject(10, new Object[]{getPointer()}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT stop() {
            return hr(_invokeNativeObject(11, new Object[]{getPointer()}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT setEventHandle(WinNT.HANDLE event) {
            return hr(_invokeNativeObject(13, new Object[]{getPointer(), event}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT getService(Guid.IID iid, PointerByReference service) {
            return hr(_invokeNativeObject(14, new Object[]{getPointer(), iid, service}, WinNT.HRESULT.class));
        }
    }

    public static final class AudioCaptureClient extends Unknown {
        AudioCaptureClient(Pointer pointer) { super(pointer); }

        WinNT.HRESULT getBuffer(PointerByReference data, IntByReference frames, IntByReference flags,
                                LongByReference devicePosition, LongByReference qpcPosition) {
            return hr(_invokeNativeObject(3, new Object[]{getPointer(), data, frames, flags,
                    devicePosition, qpcPosition}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT releaseBuffer(int frames) {
            return hr(_invokeNativeObject(4, new Object[]{getPointer(), frames}, WinNT.HRESULT.class));
        }

        WinNT.HRESULT getNextPacketSize(IntByReference frames) {
            return hr(_invokeNativeObject(5, new Object[]{getPointer(), frames}, WinNT.HRESULT.class));
        }
    }

    @Structure.FieldOrder({"wFormatTag", "nChannels", "nSamplesPerSec", "nAvgBytesPerSec", "nBlockAlign", "wBitsPerSample", "cbSize"})
    public static final class WaveFormatEx extends Structure {
        public short wFormatTag;
        public short nChannels;
        public int nSamplesPerSec;
        public int nAvgBytesPerSec;
        public short nBlockAlign;
        public short wBitsPerSample;
        public short cbSize;

        static WaveFormatEx pcm16Mono16k() {
            WaveFormatEx format = new WaveFormatEx();
            format.wFormatTag = WAVE_FORMAT_PCM;
            format.nChannels = 1;
            format.nSamplesPerSec = 16_000;
            format.wBitsPerSample = 16;
            format.nBlockAlign = 2;
            format.nAvgBytesPerSec = 32_000;
            format.cbSize = 0;
            format.write();
            return format;
        }
    }

    @Structure.FieldOrder({"fmtid", "pid"})
    public static final class PropertyKey extends Structure {
        public Guid.GUID fmtid;
        public int pid;

        PropertyKey(Guid.GUID fmtid, int pid) {
            this.fmtid = fmtid;
            this.pid = pid;
            write();
        }
    }

    @Structure.FieldOrder({"pointerValue", "padding"})
    public static final class PropVariantUnion extends Union {
        public Pointer pointerValue;
        public long longValue;
        public byte[] padding = new byte[16];
    }

    @Structure.FieldOrder({"vt", "reserved1", "reserved2", "reserved3", "value"})
    public static final class PropVariant extends Structure {
        public short vt;
        public short reserved1;
        public short reserved2;
        public short reserved3;
        public PropVariantUnion value = new PropVariantUnion();

        String stringValue() {
            read();
            value.setType(Pointer.class);
            value.read();
            return vt == VT_LPWSTR && value.pointerValue != null
                    ? value.pointerValue.getWideString(0) : "Неизвестное устройство";
        }
    }
}
