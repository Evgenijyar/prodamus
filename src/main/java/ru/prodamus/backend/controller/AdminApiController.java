package ru.prodamus.backend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import ru.prodamus.backend.model.AiCredential;
import ru.prodamus.backend.model.AppUser;
import ru.prodamus.backend.model.PromptProfile;
import ru.prodamus.backend.model.SystemConfig;
import ru.prodamus.backend.service.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminApiController {
    private final BackofficeQueryService query;
    private final UserService users;
    private final PromptProfileService prompts;
    private final AiCredentialService credentials;
    private final LiveSessionService liveSessions;
    private final SystemConfigService systemConfig;
    private final AuditService audit;

    public AdminApiController(BackofficeQueryService query, UserService users, PromptProfileService prompts,
                              AiCredentialService credentials, LiveSessionService liveSessions,
                              SystemConfigService systemConfig, AuditService audit) {
        this.query = query;
        this.users = users;
        this.prompts = prompts;
        this.credentials = credentials;
        this.liveSessions = liveSessions;
        this.systemConfig = systemConfig;
        this.audit = audit;
    }

    @GetMapping("/dashboard")
    public BackofficeQueryService.Dashboard dashboard() { return query.dashboard(); }

    @GetMapping("/users")
    public List<BackofficeQueryService.UserSummary> users() { return query.users(); }

    @GetMapping("/users/{id}")
    public BackofficeQueryService.UserDetail user(@PathVariable Long id) { return query.user(id); }

    @PostMapping("/users")
    public BackofficeQueryService.UserDetail createUser(@Valid @RequestBody UserRequest request) {
        AppUser created = users.create(request.login(), request.displayName(), request.email(), request.password(),
                request.enabled(), request.customInstructions(), safe(request.promptIds()));
        audit.record("USER_CREATED", "admin", created.getLogin(), null);
        return query.user(created.getId());
    }

    @PutMapping("/users/{id}")
    public BackofficeQueryService.UserDetail updateUser(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        AppUser updated = users.update(id, request.login(), request.displayName(), request.email(), request.password(),
                request.enabled(), request.customInstructions(), safe(request.promptIds()));
        audit.record("USER_UPDATED", "admin", updated.getLogin(), null);
        return query.user(updated.getId());
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> disableUser(@PathVariable Long id) {
        AppUser user = users.disable(id);
        liveSessions.terminateForUserByAdmin(id);
        audit.record("USER_DISABLED", "admin", user.getLogin(), null);
        return Map.of("ok", true);
    }

    @PostMapping("/users/{id}/devices/revoke")
    public Map<String, Object> revokeDevice(@PathVariable Long id, @RequestBody DeviceRevokeRequest request) {
        users.revokeDevice(id, request.deviceId());
        audit.record("DEVICE_REVOKED", "admin", String.valueOf(id), request.deviceId());
        return Map.of("ok", true);
    }

    @GetMapping("/prompts")
    public List<BackofficeQueryService.PromptView> prompts() { return query.prompts(); }

    @PostMapping("/prompts")
    public BackofficeQueryService.PromptView createPrompt(@Valid @RequestBody PromptRequest request) {
        PromptProfile created = prompts.create(request.name(), request.description(), request.systemPrompt(), request.knowledgeBase(),
                request.model(), request.enabled(), request.sortOrder());
        audit.record("PROMPT_CREATED", "admin", created.getName(), null);
        return query.prompts().stream().filter(p -> p.id().equals(created.getId())).findFirst().orElseThrow();
    }

    @PutMapping("/prompts/{id}")
    public BackofficeQueryService.PromptView updatePrompt(@PathVariable Long id, @Valid @RequestBody PromptRequest request) {
        PromptProfile updated = prompts.update(id, request.name(), request.description(), request.systemPrompt(), request.knowledgeBase(),
                request.model(), request.enabled(), request.sortOrder());
        audit.record("PROMPT_UPDATED", "admin", updated.getName(), null);
        return query.prompts().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @DeleteMapping("/prompts/{id}")
    public Map<String, Object> disablePrompt(@PathVariable Long id) {
        PromptProfile profile = prompts.disable(id);
        audit.record("PROMPT_DISABLED", "admin", profile.getName(), null);
        return Map.of("ok", true);
    }

    @GetMapping("/credentials")
    public List<BackofficeQueryService.CredentialView> credentials() { return query.credentials(); }

    @PostMapping("/credentials")
    public BackofficeQueryService.CredentialView createCredential(@Valid @RequestBody CredentialRequest request) {
        AiCredential created = credentials.create(request.name(), request.apiKey(), request.enabled(), request.maxConcurrentSessions());
        audit.record("AI_CREDENTIAL_CREATED", "admin", created.getName(), created.getKeyHint());
        return credential(created.getId());
    }

    @PutMapping("/credentials/{id}")
    public BackofficeQueryService.CredentialView updateCredential(@PathVariable Long id, @Valid @RequestBody CredentialRequest request) {
        AiCredential updated = credentials.update(id, request.name(), request.apiKey(), request.enabled(), request.maxConcurrentSessions());
        audit.record("AI_CREDENTIAL_UPDATED", "admin", updated.getName(), updated.getKeyHint());
        return credential(id);
    }

    @DeleteMapping("/credentials/{id}")
    public Map<String, Object> disableCredential(@PathVariable Long id) {
        AiCredential credential = credentials.disable(id);
        audit.record("AI_CREDENTIAL_DISABLED", "admin", credential.getName(), null);
        return Map.of("ok", true);
    }

    @PostMapping("/credentials/{id}/test")
    public AiCredentialService.TestResult testCredential(@PathVariable Long id) {
        AiCredentialService.TestResult result = credentials.test(id);
        audit.record("AI_CREDENTIAL_TEST", "admin", String.valueOf(id), result.message());
        return result;
    }

    @GetMapping("/sessions")
    public List<BackofficeQueryService.SessionView> sessions() { return query.sessions(); }

    @PostMapping("/sessions/{id}/terminate")
    public Map<String, Object> terminate(@PathVariable UUID id) {
        liveSessions.terminateByAdmin(id);
        audit.record("LIVE_SESSION_TERMINATED", "admin", id.toString(), null);
        return Map.of("ok", true);
    }

    @GetMapping("/system")
    public SystemView system() { return SystemView.from(systemConfig.get()); }

    @PutMapping("/system")
    public SystemView system(@Valid @RequestBody SystemRequest request) {
        SystemConfig config = systemConfig.update(request.globalPrompt(), request.minimumClientVersion(),
                request.latestClientVersion(), request.defaultModel(), request.featureExpandedMode(),
                request.featureManualClientContext());
        audit.record("SYSTEM_CONFIG_UPDATED", "admin", "system", null);
        return SystemView.from(config);
    }

    @GetMapping("/audit")
    public List<BackofficeQueryService.AuditView> audit() { return query.audit(); }

    private BackofficeQueryService.CredentialView credential(Long id) {
        return query.credentials().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("AI-ключ не найден."));
    }

    private Set<Long> safe(Set<Long> ids) { return ids == null ? Set.of() : ids; }

    public record DeviceRevokeRequest(@NotBlank(message = "deviceId обязателен.") String deviceId) {}

    public record UserRequest(@NotBlank(message = "Укажите логин.") String login,
                              @NotBlank(message = "Укажите имя пользователя.") String displayName,
                              String email, String password, boolean enabled, String customInstructions,
                              Set<Long> promptIds) {}

    public record PromptRequest(@NotBlank(message = "Укажите название роли.") String name, String description,
                                String systemPrompt, String knowledgeBase, String model,
                                boolean enabled, int sortOrder) {}

    public record CredentialRequest(@NotBlank(message = "Укажите название ключа.") String name,
                                    String apiKey, boolean enabled, int maxConcurrentSessions) {}

    public record SystemRequest(String globalPrompt,
                                @NotBlank(message = "Укажите минимальную версию клиента.") String minimumClientVersion,
                                @NotBlank(message = "Укажите актуальную версию клиента.") String latestClientVersion,
                                @NotBlank(message = "Укажите модель Gemini.") String defaultModel,
                                boolean featureExpandedMode, boolean featureManualClientContext) {}

    public record SystemView(String globalPrompt, String minimumClientVersion, String latestClientVersion,
                             String defaultModel, boolean featureExpandedMode, boolean featureManualClientContext,
                             java.time.Instant updatedAt) {
        static SystemView from(SystemConfig c) {
            return new SystemView(c.getGlobalPrompt(), c.getMinimumClientVersion(), c.getLatestClientVersion(),
                    c.getDefaultModel(), c.isFeatureExpandedMode(), c.isFeatureManualClientContext(), c.getUpdatedAt());
        }
    }
}
