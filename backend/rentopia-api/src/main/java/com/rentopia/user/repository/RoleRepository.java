package com.rentopia.user.repository;

import com.rentopia.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Short> {

    Optional<Role> findByCode(String code);
}
