package ru.prodamus.client.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class WindowsPrivacyService {
    private static final Logger log = LoggerFactory.getLogger(WindowsPrivacyService.class);
    private static final int WDA_NONE = 0;
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x11;

    interface User32Extra extends StdCallLibrary {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class, W32APIOptions.UNICODE_OPTIONS);

        WinDef.HWND FindWindowW(Pointer className, String windowName);

        boolean SetWindowDisplayAffinity(WinDef.HWND window, int affinity);
    }

    public boolean setExcluded(String windowTitle, boolean excluded) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            log.warn("Capture protection skipped: operating system is not Windows");
            return false;
        }
        WinDef.HWND handle = User32Extra.INSTANCE.FindWindowW(Pointer.NULL, windowTitle);
        if (handle == null) {
            log.warn("Capture protection failed: window not found by title={}", windowTitle);
            return false;
        }
        boolean applied = User32Extra.INSTANCE.SetWindowDisplayAffinity(handle,
                excluded ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE);
        log.info("Capture protection changed: excluded={}, result={}, hwnd={}", excluded, applied, handle);
        return applied;
    }
}
