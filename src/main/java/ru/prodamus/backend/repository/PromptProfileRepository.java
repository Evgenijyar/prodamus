package ru.prodamus.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.prodamus.backend.model.PromptProfile;

import java.util.Collection;
import java.util.List;

public interface PromptProfileRepository extends JpaRepository<PromptProfile, Long> {
    boolean existsByNameIgnoreCase(String name);
    List<PromptProfile> findAllByOrderBySortOrderAscNameAsc();
    List<PromptProfile> findAllByEnabledTrueOrderBySortOrderAscNameAsc();
    List<PromptProfile> findAllByIdIn(Collection<Long> ids);
}
