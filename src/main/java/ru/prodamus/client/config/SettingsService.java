package ru.prodamus.client.config;

import com.sun.jna.platform.win32.Crypt32Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.prefs.Preferences;

@Service
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String KEY_REFRESH = "refreshTokenProtected";
    private static final String KEY_DEVICE_ID = "deviceId";
    private static final String KEY_LAST_LOGIN = "lastLogin";
    private final Preferences preferences = Preferences.userNodeForPackage(SettingsService.class);

    public AppSettings load() {
        AppSettings defaults = AppSettings.defaults();
        return new AppSettings(
                preferences.get("microphoneDeviceId", ""),
                preferences.get("loopbackDeviceId", ""),
                preferences.getInt("vadThreshold", defaults.vadThreshold()),
                preferences.getInt("silenceMillis", defaults.silenceMillis()),
                preferences.getBoolean("excludeFromCapture", defaults.excludeFromCapture()),
                preferences.getDouble("overlayOpacity", defaults.overlayOpacity()),
                preferences.getBoolean("expandedPreferred", defaults.expandedPreferred()),
                preferences.getBoolean("activeListening", defaults.activeListening()),
                preferences.getInt("activeListeningIntervalSeconds", defaults.activeListeningIntervalSeconds()),
                preferences.getLong("lastRoleId", defaults.lastRoleId())
        );
    }

    public void save(AppSettings settings) {
        preferences.put("microphoneDeviceId", value(settings.microphoneDeviceId()));
        preferences.put("loopbackDeviceId", value(settings.loopbackDeviceId()));
        preferences.putInt("vadThreshold", settings.vadThreshold());
        preferences.putInt("silenceMillis", settings.silenceMillis());
        preferences.putBoolean("excludeFromCapture", settings.excludeFromCapture());
        preferences.putDouble("overlayOpacity", settings.overlayOpacity());
        preferences.putBoolean("expandedPreferred", settings.expandedPreferred());
        preferences.putBoolean("activeListening", settings.activeListening());
        preferences.putInt("activeListeningIntervalSeconds", settings.activeListeningIntervalSeconds());
        preferences.putLong("lastRoleId", settings.lastRoleId());
    }

    public String deviceId() {
        String existing = preferences.get(KEY_DEVICE_ID, "").trim();
        if (!existing.isBlank()) return existing;
        String generated = UUID.randomUUID().toString();
        preferences.put(KEY_DEVICE_ID, generated);
        return generated;
    }

    public String deviceName() {
        String computer = System.getenv("COMPUTERNAME");
        if (computer == null || computer.isBlank()) computer = System.getProperty("user.name", "Windows PC");
        return computer + " / Prodamus";
    }

    public String lastLogin() { return preferences.get(KEY_LAST_LOGIN, ""); }
    public void setLastLogin(String login) { preferences.put(KEY_LAST_LOGIN, value(login)); }

    public String loadRefreshToken() {
        return decrypt(preferences.get(KEY_REFRESH, ""));
    }

    public void storeRefreshToken(String token) {
        if (token == null || token.isBlank()) {
            clearRefreshToken();
            return;
        }
        preferences.put(KEY_REFRESH, encrypt(token));
        log.info("Persistent refresh token stored using Windows DPAPI");
    }

    public void clearRefreshToken() {
        preferences.remove(KEY_REFRESH);
    }

    private String encrypt(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] protectedData = Crypt32Util.cryptProtectData(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(protectedData);
        } catch (RuntimeException exception) {
            log.error("Windows DPAPI encryption failed", exception);
            throw new IllegalStateException("Не удалось безопасно сохранить сессию входа Windows", exception);
        }
    }

    private String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            byte[] clear = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(encoded));
            return new String(clear, StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            log.warn("Stored refresh token cannot be decrypted and will be removed", exception);
            clearRefreshToken();
            return "";
        }
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }
}
