package com.fonja.comment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fonja.comment.dto.CommentPage;
import com.fonja.comment.service.CommentService;
import com.fonja.common.config.SecurityConfig;
import com.fonja.common.security.OAuth2LoginSuccessHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Against the real {@link SecurityConfig}: reading is public, writing needs a token. */
@WebMvcTest(controllers = CommentController.class)
@Import(SecurityConfig.class)
@TestPropertySource(
        properties = {
            "fonja.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes",
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret"
        })
class CommentControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CommentService commentService;

    /** Only present so SecurityConfig's OAuth2 login chain can be built; never invoked here. */
    @MockitoBean private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    private static CommentPage empty() {
        return new CommentPage(List.of(), new CommentPage.PageMeta(10, 0, 0, 0), 0);
    }

    @Test
    void anyoneCanReadComments() throws Exception {
        when(commentService.list(isNull(), eq(false), eq(5L), any())).thenReturn(empty());

        mockMvc.perform(get("/api/v1/campaigns/5/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.content").isArray())
                .andExpect(jsonPath("$.responseData.page.totalElements").value(0))
                .andExpect(jsonPath("$.responseData.totalCount").value(0));
    }

    @Test
    void anAdminTokenIsPassedThroughSoTheyCanModerate() throws Exception {
        when(commentService.list(eq(9L), eq(true), eq(5L), any())).thenReturn(empty());

        mockMvc.perform(
                        get("/api/v1/campaigns/5/comments")
                                .with(
                                        jwt().jwt(
                                                        b ->
                                                                b.subject("9")
                                                                        .claim(
                                                                                "roles",
                                                                                List.of(
                                                                                        "DONOR",
                                                                                        "ADMIN")))))
                .andExpect(status().isOk());
    }

    @Test
    void postingNeedsAToken() throws Exception {
        mockMvc.perform(
                        post("/api/v1/campaigns/5/comments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"body\":\"Hello\"}"))
                .andExpect(status().isUnauthorized());
        verify(commentService, never()).post(any(), any(), any());
    }

    @Test
    void anEmptyCommentIsAValidationError() throws Exception {
        mockMvc.perform(
                        post("/api/v1/campaigns/5/comments")
                                .with(jwt().jwt(b -> b.subject("2")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("01"));
    }

    @Test
    void deletingNeedsAToken() throws Exception {
        mockMvc.perform(delete("/api/v1/campaigns/5/comments/7"))
                .andExpect(status().isUnauthorized());
        verify(commentService, never()).delete(any(), anyBoolean(), any(), any());
    }
}
