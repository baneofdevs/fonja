package com.fonja.user.controller;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.user.dto.UserResponse;
import com.fonja.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * The caller's own profile. There is deliberately no "get user by id" endpoint — public
     * profiles are Phase 2 (technical spec §1.2).
     */
    @GetMapping("/me")
    public CustomResponse<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(userService.getProfile(JwtService.userIdFrom(jwt)));
    }
}
