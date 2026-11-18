package com.rentopia.user.repository;

import com.rentopia.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Callers must pass a normalized (lower-case) email. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, UUID id);
}
