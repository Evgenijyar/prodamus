package ru.prodamus.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.model.PromptProfile;
import ru.prodamus.backend.repository.PromptProfileRepository;

import java.util.List;

@Service
public class PromptProfileService {
    private final PromptProfileRepository repository;
    private final String defaultModel;

    public PromptProfileService(PromptProfileRepository repository,
                                @Value("${prodamus.gemini.default-model}") String defaultModel) {
        this.repository = repository;
        this.defaultModel = defaultModel;
    }

    @Transactional(readOnly = true)
    public List<PromptProfile> list() { return repository.findAllByOrderBySortOrderAscNameAsc(); }

    @Transactional(readOnly = true)
    public PromptProfile require(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Роль не найдена."));
    }

    @Transactional
    public PromptProfile create(String name, String description, String systemPrompt, String knowledgeBase,
                                String model, boolean enabled, int sortOrder) {
        String normalized = required(name, "Название роли обязательно.");
        if (repository.existsByNameIgnoreCase(normalized)) {
            throw ApiException.conflict("PROMPT_NAME_EXISTS", "Роль с таким названием уже существует.");
        }
        PromptProfile profile = new PromptProfile();
        apply(profile, normalized, description, systemPrompt, knowledgeBase, model, enabled, sortOrder);
        return repository.save(profile);
    }

    @Transactional
    public PromptProfile update(Long id, String name, String description, String systemPrompt, String knowledgeBase,
                                String model, boolean enabled, int sortOrder) {
        PromptProfile profile = require(id);
        String normalized = required(name, "Название роли обязательно.");
        repository.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(item -> !item.getId().equals(id) && item.getName().equalsIgnoreCase(normalized))
                .findAny().ifPresent(item -> { throw ApiException.conflict("PROMPT_NAME_EXISTS", "Роль с таким названием уже существует."); });
        apply(profile, normalized, description, systemPrompt, knowledgeBase, model, enabled, sortOrder);
        profile.setVersion(profile.getVersion() + 1);
        return repository.save(profile);
    }

    @Transactional
    public PromptProfile disable(Long id) {
        PromptProfile profile = require(id);
        profile.setEnabled(false);
        profile.setVersion(profile.getVersion() + 1);
        return repository.save(profile);
    }

    private void apply(PromptProfile profile, String name, String description, String systemPrompt,
                       String knowledgeBase, String model, boolean enabled, int sortOrder) {
        profile.setName(name);
        profile.setDescription(description == null || description.isBlank() ? null : description.trim());
        profile.setSystemPrompt(systemPrompt == null ? "" : systemPrompt.trim());
        profile.setKnowledgeBase(knowledgeBase == null ? "" : knowledgeBase.trim());
        profile.setModel(model == null || model.isBlank() ? defaultModel : model.trim());
        profile.setEnabled(enabled);
        profile.setSortOrder(Math.max(-10_000, Math.min(10_000, sortOrder)));
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        return value.trim();
    }
}
