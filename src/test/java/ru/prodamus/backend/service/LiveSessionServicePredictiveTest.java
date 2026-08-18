package ru.prodamus.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.model.AiCredential;
import ru.prodamus.backend.model.AppUser;
import ru.prodamus.backend.model.LiveSession;
import ru.prodamus.backend.model.PromptProfile;
import ru.prodamus.backend.repository.AiCredentialRepository;
import ru.prodamus.backend.repository.AppUserRepository;
import ru.prodamus.backend.repository.LiveSessionRepository;
import ru.prodamus.backend.repository.PromptProfileRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiveSessionServicePredictiveTest {
    @Mock LiveSessionRepository sessions;
    @Mock AiCredentialRepository credentials;
    @Mock AppUserRepository users;
    @Mock PromptProfileRepository prompts;
    @Mock AiCredentialService credentialService;
    @Mock GeminiTokenService gemini;

    private LiveSessionService service;
    private AppUser user;
    private PromptProfile prompt;
    private AiCredential first;
    private AiCredential second;

    @BeforeEach
    void setUp() {
        user = mock(AppUser.class);
        prompt = mock(PromptProfile.class);
        first = credential(101L);
        second = credential(202L);
        when(user.isEnabled()).thenReturn(true);
        when(user.getPromptProfiles()).thenReturn(Set.of(prompt));
        when(user.getCustomInstructions()).thenReturn("");
        when(prompt.getId()).thenReturn(7L);
        when(prompt.isEnabled()).thenReturn(true);
        when(prompt.getModel()).thenReturn("gemini-3.1-flash-live-preview");
        when(prompt.getVersion()).thenReturn(3);
        when(prompt.getSystemPrompt()).thenReturn("Role prompt");
        when(prompt.getKnowledgeBase()).thenReturn("Knowledge");
        when(users.lockById(1L)).thenReturn(Optional.of(user));
        when(prompts.findById(7L)).thenReturn(Optional.of(prompt));
        when(sessions.findByUser_IdAndStatusIn(anyLong(), anyList())).thenReturn(List.of());
        when(sessions.countLeasedForCredential(anyLong(), any(Instant.class))).thenReturn(0L);

        Map<java.util.UUID, LiveSession> stored = new HashMap<>();
        when(sessions.saveAndFlush(any(LiveSession.class))).thenAnswer(invocation -> {
            LiveSession value = invocation.getArgument(0);
            stored.put(value.getId(), value);
            return value;
        });
        when(sessions.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(sessions.save(any(LiveSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(credentialService.decrypt(first)).thenReturn("key-1");
        when(credentialService.decrypt(second)).thenReturn("key-2");

        service = new LiveSessionService(sessions, credentials, users, prompts, credentialService, gemini,
                transactionTemplate(), 120);
    }

    @Test
    void dualModeUsesTwoDifferentCredentialsAndDifferentPrompts() {
        when(credentials.lockEnabledCredentials()).thenReturn(List.of(first, second));
        Instant expires = Instant.now().plusSeconds(3600);
        when(gemini.createConstrainedToken(anyString(), anyString(), anyString()))
                .thenReturn(token("token-tactical", expires), token("token-predictive", expires));

        LiveSessionService.PredictiveSessionBundle result = service.startPredictive(
                1L, "device", 7L, "device", "1.4.0-predictive.1", "", true);

        assertThat(result.mode()).isEqualTo("DUAL");
        assertThat(result.tactical().ephemeralToken()).isEqualTo("token-tactical");
        assertThat(result.predictive().ephemeralToken()).isEqualTo("token-predictive");
        assertThat(result.predictive().sessionId()).isNotEqualTo(result.tactical().sessionId());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(2)).createConstrainedToken(key.capture(), eq("gemini-3.1-flash-live-preview"),
                instruction.capture());
        assertThat(key.getAllValues()).containsExactly("key-1", "key-2");
        assertThat(instruction.getAllValues().get(0)).contains("быстрая тактическая сессия");
        assertThat(instruction.getAllValues().get(1)).contains("фоновая предиктивная сессия");
    }

    @Test
    void singleSessionUsesOriginalSalesHelperPromptWithRoleAndKnowledge() {
        when(user.getCustomInstructions()).thenReturn("User custom instructions must not leak");
        when(credentials.lockEnabledCredentials()).thenReturn(List.of(first));
        Instant expires = Instant.now().plusSeconds(3600);
        when(gemini.createConstrainedToken(anyString(), anyString(), anyString()))
                .thenReturn(token("single-token", expires));

        LiveSessionService.SessionDescriptor result = service.start(
                1L, "device", 7L, "device", "2.0.0", "Manual client context must not leak");

        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(gemini).createConstrainedToken(eq("key-1"), eq("gemini-3.1-flash-live-preview"),
                instruction.capture());
        assertThat(instruction.getValue())
                .contains("Role prompt")
                .contains("Knowledge")
                .contains("незаметный ассистент менеджера по продажам")
                .contains("[КЛИЕНТ]")
                .contains("[МЕНЕДЖЕР]")
                .doesNotContain("Global")
                .doesNotContain("User custom instructions must not leak")
                .doesNotContain("Manual client context must not leak");
        assertThat(result.systemInstruction()).isEqualTo(instruction.getValue());
    }

    @Test
    void predictiveV2UsesHiddenScenariosAndSingleVisibleRecommender() {
        when(credentials.lockEnabledCredentials()).thenReturn(List.of(first, second));
        Instant expires = Instant.now().plusSeconds(3600);
        when(gemini.createConstrainedToken(anyString(), anyString(), anyString()))
                .thenReturn(token("token-recommender", expires), token("token-forecaster", expires));

        LiveSessionService.PredictiveSessionBundle result = service.startPredictiveV2(
                1L, "device", 7L, "device", "1.5.0-predictive.2", "");

        assertThat(result.mode()).isEqualTo("PREDICTIVE_V2");
        assertThat(result.tactical().ephemeralToken()).isEqualTo("token-recommender");
        assertThat(result.predictive().ephemeralToken()).isEqualTo("token-forecaster");

        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(2)).createConstrainedToken(anyString(), eq("gemini-3.1-flash-live-preview"),
                instruction.capture());
        assertThat(instruction.getAllValues().get(0))
                .contains("единственный видимый рекомендатель")
                .contains("[СКРЫТЫЙ ПРОГНОЗ]")
                .contains("CLIENT_CONTINUATION")
                .contains("CLIENT_FINAL_RECOVERY")
                .contains("ответ — запрещён");
        assertThat(instruction.getAllValues().get(1))
                .contains("ровно три взаимоисключающих")
                .contains("CLIENT_FINAL")
                .contains("ПРИЗНАКИ");
    }

    @Test
    void dualModeDoesNotFallBackWhenOnlyOneDistinctCredentialIsFree() {
        when(credentials.lockEnabledCredentials()).thenReturn(List.of(first));

        assertThatThrownBy(() -> service.startPredictive(
                1L, "device", 7L, "device", "1.4.0-predictive.1", "", true))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("NO_AI_CAPACITY");
                    assertThat(error.getMessage()).contains("два разных свободных AI-ключа");
                });
        verify(gemini, never()).createConstrainedToken(anyString(), anyString(), anyString());
    }

    private AiCredential credential(long id) {
        AiCredential credential = mock(AiCredential.class);
        when(credential.getId()).thenReturn(id);
        when(credential.getMaxConcurrentSessions()).thenReturn(1);
        return credential;
    }

    private GeminiTokenService.TokenResult token(String value, Instant expires) {
        return new GeminiTokenService.TokenResult(value, expires, Instant.now().plusSeconds(60),
                "wss://example.test/live", "gemini-3.1-flash-live-preview");
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        });
    }
}
