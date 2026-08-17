package ru.prodamus.backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.prodamus.backend.model.AppUser;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByLoginIgnoreCase(String login);
    boolean existsByLoginIgnoreCase(String login);
    List<AppUser> findAllByOrderByDisplayNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(@Param("id") Long id);
}
