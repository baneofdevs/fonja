package com.fonja.user.controller;

import com.fonja.campaign.service.CampaignMediaService;
import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.user.dto.ChangePasswordRequest;
import com.fonja.user.dto.OnboardingRequest;
import com.fonja.user.dto.UpdateInterestsRequest;
import com.fonja.user.dto.UpdateProfileRequest;
import com.fonja.user.dto.UserResponse;
import com.fonja.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final CampaignMediaService mediaService;

    public UserController(UserService userService, CampaignMediaService mediaService) {
        this.userService = userService;
        this.mediaService = mediaService;
    }

    /**
     * The caller's own profile. There is deliberately no "get user by id" endpoint — public
     * profiles are Phase 2 (technical spec §1.2).
     */
    @GetMapping("/me")
    public CustomResponse<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(userService.getProfile(JwtService.userIdFrom(jwt)));
    }

    @PatchMapping("/me")
    public CustomResponse<UserResponse> updateProfile(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return CustomResponse.success(
                "Profile updated", userService.updateProfile(JwtService.userIdFrom(jwt), request));
    }

    @PutMapping("/me/onboarding")
    public CustomResponse<UserResponse> completeOnboarding(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OnboardingRequest request) {
        return CustomResponse.success(
                userService.completeOnboarding(JwtService.userIdFrom(jwt), request));
    }

    @PutMapping("/me/interests")
    public CustomResponse<UserResponse> replaceInterests(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateInterestsRequest request) {
        return CustomResponse.success(
                "Interests updated",
                userService.replaceInterests(JwtService.userIdFrom(jwt), request.interests()));
    }

    /**
     * Uploads a new avatar. The image goes through the same checks and storage as campaign images
     * and is served from the same public route; the upload and the assignment happen here together
     * so the avatar can only ever be an image the caller uploaded.
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CustomResponse<UserResponse> uploadAvatar(
            @AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) {
        Long userId = JwtService.userIdFrom(jwt);
        Long mediaId = mediaService.upload(userId, file).id();
        return CustomResponse.success("Photo updated", userService.setAvatar(userId, mediaId));
    }

    @DeleteMapping("/me/avatar")
    public CustomResponse<UserResponse> removeAvatar(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(
                "Photo removed", userService.setAvatar(JwtService.userIdFrom(jwt), null));
    }

    @PostMapping("/me/password")
    public CustomResponse<Void> changePassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(JwtService.userIdFrom(jwt), request);
        return CustomResponse.success("Password updated", null);
    }
}
