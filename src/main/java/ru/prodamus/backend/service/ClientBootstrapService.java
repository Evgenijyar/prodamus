package ru.prodamus.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.model.AppUser;
import ru.prodamus.backend.model.PromptProfile;
import ru.prodamus.backend.model.SystemConfig;
import ru.prodamus.backend.repository.AppUserRepository;

import java.util.List;

@Service
public class ClientBootstrapService {
    private final AppUserRepository users;
    private final SystemConfigService configService;

    public ClientBootstrapService(AppUserRepository users, SystemConfigService configService) {
        this.users = users;
        this.configService = configService;
    }

    @Transactional(readOnly = true)
    public Bootstrap bootstrap(Long userId, String clientVersion) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Пользователь не найден."));
        if (!user.isEnabled()) throw ApiException.forbidden("Доступ пользователя отключён.");
        SystemConfig config = configService.get();
        List<Role> roles = user.getPromptProfiles().stream().filter(PromptProfile::isEnabled)
                .map(p -> new Role(p.getId(), p.getName(), p.getDescription()))
                .toList();
        String version = clientVersion == null || clientVersion.isBlank() ? "0.0.0" : clientVersion.trim();
        return new Bootstrap(
                new UserInfo(user.getId(), user.getLogin(), user.getDisplayName()),
                roles,
                new Features(config.isFeatureExpandedMode(), config.isFeatureManualClientContext()),
                new VersionInfo(config.getMinimumClientVersion(), config.getLatestClientVersion(),
                        compareVersions(version, config.getMinimumClientVersion()) < 0,
                        compareVersions(version, config.getLatestClientVersion()) < 0)
        );
    }

    private int compareVersions(String left, String right) {
        int[] a = numeric(left); int[] b = numeric(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0; int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private int[] numeric(String version) {
        String base = version == null ? "" : version.split("[-+]", 2)[0];
        String[] parts = base.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", "")); }
            catch (RuntimeException ignored) { result[i] = 0; }
        }
        return result;
    }

    public record Bootstrap(UserInfo user, List<Role> roles, Features features, VersionInfo version) {}
    public record UserInfo(Long id, String login, String displayName) {}
    public record Role(Long id, String name, String description) {}
    public record Features(boolean expandedMode, boolean manualClientContext) {}
    public record VersionInfo(String minimumSupported, String latest, boolean updateRequired, boolean updateAvailable) {}
}
