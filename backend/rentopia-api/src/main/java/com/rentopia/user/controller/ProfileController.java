package com.rentopia.user.controller;

import com.rentopia.common.security.CurrentUser;
import com.rentopia.user.dto.ProfileDtos.ProfileResponse;
import com.rentopia.user.dto.ProfileDtos.UpdateProfileRequest;
import com.rentopia.user.service.ProfileService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profile")
    public ProfileResponse getProfile() {
        return profileService.get(CurrentUser.id());
    }

    @PutMapping("/profile")
    public ProfileResponse updateProfile(@Valid @RequestBody UpdateProfileRequest req) {
        return profileService.update(CurrentUser.id(), req);
    }
}
