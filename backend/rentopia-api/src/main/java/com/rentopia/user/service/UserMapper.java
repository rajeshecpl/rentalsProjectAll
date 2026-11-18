package com.rentopia.user.service;

import com.rentopia.auth.dto.AuthDtos.UserSummary;
import com.rentopia.user.dto.ProfileDtos.ProfileResponse;
import com.rentopia.user.entity.CustomerProfile;
import com.rentopia.user.entity.RoleCode;
import com.rentopia.user.entity.User;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserSummary toSummary(User u) {
        return new UserSummary(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(),
                u.getEmailVerifiedAt() != null, u.getPhoneVerifiedAt() != null, roleNames(u));
    }

    public static ProfileResponse toProfile(User u, CustomerProfile p) {
        return new ProfileResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(),
                u.getEmailVerifiedAt() != null, u.getPhoneVerifiedAt() != null, roleNames(u),
                p == null ? null : p.getDateOfBirth(), p == null ? null : p.getGender(), u.getCreatedAt());
    }

    private static Set<String> roleNames(User u) {
        return u.roleCodes().stream().map(RoleCode::name).collect(Collectors.toCollection(TreeSet::new));
    }
}
