package com.rentopia.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Reference data seeded by V1 migration; codes match {@link RoleCode}. */
@Entity
@Table(name = "roles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {

    @Id
    private Short id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(length = 200)
    private String description;

    public RoleCode toCode() {
        return RoleCode.valueOf(code);
    }
}
