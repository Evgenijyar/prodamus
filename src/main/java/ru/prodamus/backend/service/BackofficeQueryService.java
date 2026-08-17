package ru.prodamus.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.model.*;
import ru.prodamus.backend.repository.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BackofficeQueryService {
    private final AppUserRepository users;
    private final PromptProfileRepository prompts;
    private final AiCredentialRepository credentials;
    private final LiveSessionRepository sessions;
    private final AuditEventRepository audit;
    private final ClientRefreshTokenRepository refreshTokens;

    public BackofficeQueryService(AppUserRepository users, PromptProfileRepository prompts,
                                  AiCredentialRepository credentials, LiveSessionRepository sessions,
                                  AuditEventRepository audit, ClientRefreshTokenRepository refreshTokens) {
        this.users = users; this.prompts = prompts; this.credentials = credentials; this.sessions = sessions; this.audit = audit; this.refreshTokens = refreshTokens;
    }

    @Transactional(readOnly = true)
    public List<UserSummary> users() {
        Instant now = Instant.now();
        return users.findAllByOrderByDisplayNameAsc().stream().map(u -> new UserSummary(
                u.getId(), u.getLogin(), u.getDisplayName(), u.getEmail(), u.isEnabled(),
                u.getPromptProfiles().size(), sessions.countActiveForUser(u.getId(), now), u.getLastLoginAt()
        )).toList();
    }

    @Transactional(readOnly = true)
    public UserDetail user(Long id) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден."));
        List<SessionView> recent = sessions.findTop30ByUser_IdOrderByStartedAtDesc(id).stream().map(this::session).toList();
        Instant now = Instant.now();
        List<DeviceView> devices = refreshTokens.findByUser_IdAndRevokedAtIsNullOrderByCreatedAtDesc(id).stream()
                .filter(t -> t.getExpiresAt().isAfter(now))
                .collect(java.util.stream.Collectors.toMap(
                        ClientRefreshToken::getDeviceId,
                        t -> new DeviceView(t.getDeviceId(), t.getDeviceName(), t.isPersistent(), t.getCreatedAt(), t.getLastUsedAt(), t.getExpiresAt()),
                        (a, b) -> a.createdAt().isAfter(b.createdAt()) ? a : b,
                        java.util.LinkedHashMap::new
                )).values().stream().toList();
        return new UserDetail(u.getId(), u.getLogin(), u.getDisplayName(), u.getEmail(), u.isEnabled(), u.getCustomInstructions(),
                u.getPromptProfiles().stream().map(PromptProfile::getId).toList(), u.getCreatedAt(), u.getUpdatedAt(), u.getLastLoginAt(), recent, devices);
    }

    @Transactional(readOnly = true)
    public List<PromptView> prompts() {
        return prompts.findAllByOrderBySortOrderAscNameAsc().stream().map(p -> new PromptView(
                p.getId(), p.getName(), p.getDescription(), p.getSystemPrompt(), p.getKnowledgeBase(), p.getModel(),
                p.isEnabled(), p.getVersion(), p.getSortOrder(), p.getUpdatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public List<CredentialView> credentials() {
        Instant now = Instant.now();
        return credentials.findAllByOrderByNameAsc().stream().map(c -> new CredentialView(
                c.getId(), c.getName(), c.getProvider(), c.getKeyHint(), c.isEnabled(), c.getMaxConcurrentSessions(),
                sessions.countLeasedForCredential(c.getId(), now), c.getHealthStatus(), c.getLastError(), c.getLastCheckedAt(), c.getUpdatedAt()
        )).toList();
    }

    @Transactional(readOnly = true)
    public List<SessionView> sessions() { return sessions.findTop200ByOrderByStartedAtDesc().stream().map(this::session).toList(); }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        Instant now = Instant.now();
        long enabledUsers = users.findAll().stream().filter(AppUser::isEnabled).count();
        long enabledPrompts = prompts.findAll().stream().filter(PromptProfile::isEnabled).count();
        long enabledKeys = credentials.findAll().stream().filter(AiCredential::isEnabled).count();
        long capacity = credentials.findAll().stream().filter(AiCredential::isEnabled).mapToLong(AiCredential::getMaxConcurrentSessions).sum();
        return new Dashboard(enabledUsers, enabledPrompts, enabledKeys, capacity, sessions.countActive(now));
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit() { return audit.findTop100ByOrderByCreatedAtDesc().stream().map(a -> new AuditView(a.getId(), a.getCreatedAt(), a.getEventType(), a.getActor(), a.getSubject(), a.getDetail())).toList(); }

    private SessionView session(LiveSession s) {
        return new SessionView(s.getId(), s.getStatus(), s.getUser().getId(), s.getUser().getDisplayName(),
                s.getPromptProfile().getId(), s.getPromptProfile().getName(), s.getPromptVersion(), s.getAiCredential().getName(),
                s.getDeviceId(), s.getClientVersion(), s.getStartedAt(), s.getActivatedAt(), s.getClosedAt(),
                s.getLeaseExpiresAt(), s.getCloseReason());
    }

    public record UserSummary(Long id, String login, String displayName, String email, boolean enabled,
                              int promptCount, long activeSessions, Instant lastLoginAt) {}
    public record UserDetail(Long id, String login, String displayName, String email, boolean enabled,
                             String customInstructions, List<Long> promptIds, Instant createdAt, Instant updatedAt,
                             Instant lastLoginAt, List<SessionView> recentSessions, List<DeviceView> devices) {}
    public record DeviceView(String deviceId, String deviceName, boolean persistent, Instant createdAt, Instant lastUsedAt, Instant expiresAt) {}
    public record PromptView(Long id, String name, String description, String systemPrompt, String knowledgeBase,
                             String model, boolean enabled, int version, int sortOrder, Instant updatedAt) {}
    public record CredentialView(Long id, String name, String provider, String keyHint, boolean enabled,
                                 int maxConcurrentSessions, long activeSessions, String healthStatus,
                                 String lastError, Instant lastCheckedAt, Instant updatedAt) {}
    public record SessionView(UUID id, String status, Long userId, String userName, Long promptProfileId,
                              String promptName, int promptVersion, String credentialName, String deviceId, String clientVersion,
                              Instant startedAt, Instant activatedAt, Instant closedAt, Instant leaseExpiresAt,
                              String closeReason) {}
    public record Dashboard(long enabledUsers, long enabledPrompts, long enabledCredentials, long totalCapacity, long activeSessions) {}
    public record AuditView(Long id, Instant createdAt, String eventType, String actor, String subject, String detail) {}
}
