package com.rentopia.user.service;

import com.rentopia.common.exception.ApiException;
import com.rentopia.user.dto.ProfileDtos.ProfileResponse;
import com.rentopia.user.dto.ProfileDtos.UpdateProfileRequest;
import com.rentopia.user.entity.CustomerProfile;
import com.rentopia.user.entity.User;
import com.rentopia.user.repository.CustomerProfileRepository;
import com.rentopia.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class ProfileService {

    private final UserRepository users;
    private final CustomerProfileRepository customerProfiles;

    public ProfileService(UserRepository users, CustomerProfileRepository customerProfiles) {
        this.users = users;
        this.customerProfiles = customerProfiles;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID userId) {
        User user = load(userId);
        return UserMapper.toProfile(user, customerProfiles.findById(userId).orElse(null));
    }

    @Transactional
    public ProfileResponse update(UUID userId, UpdateProfileRequest req) {
        User user = load(userId);
        if (!Objects.equals(user.getPhone(), req.phone())) {
            if (req.phone() != null && users.existsByPhoneAndIdNot(req.phone(), userId)) {
                throw ApiException.conflict("PHONE_TAKEN", "An account with this phone number already exists");
            }
            user.setPhone(req.phone());
            user.setPhoneVerifiedAt(null);  // a new number must be verified again
        }
        user.setFullName(req.fullName().trim());

        CustomerProfile profile = customerProfiles.findById(userId)
                .orElseGet(() -> customerProfiles.save(new CustomerProfile(userId)));
        profile.setDateOfBirth(req.dateOfBirth());
        profile.setGender(req.gender());

        users.flush();
        return UserMapper.toProfile(user, profile);
    }

    private User load(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }
}
