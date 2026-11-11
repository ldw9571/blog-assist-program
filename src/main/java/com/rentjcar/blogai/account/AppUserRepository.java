package com.rentjcar.blogai.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByAdminTrue();
    Optional<AppUser> findFirstByAdminTrueOrderByIdAsc();
    List<AppUser> findAllByOrderByIdDesc();
    long countByStatus(UserStatus status);
}
