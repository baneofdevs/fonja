package com.fonja.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import com.fonja.verification.dto.SubmitVerificationRequest;
import com.fonja.verification.entity.DocType;
import com.fonja.verification.entity.VerificationDocument;
import com.fonja.verification.entity.VerificationRequest;
import com.fonja.verification.entity.VerificationStatus;
import com.fonja.verification.entity.VerificationType;
import com.fonja.verification.repository.VerificationDocumentRepository;
import com.fonja.verification.repository.VerificationRequestRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Covers the verification gate's own rules, with the upload path first: an admin reviewer opens
 * whatever lands here, so what the endpoint accepts is a security boundary, not a formatting
 * preference.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ADMIN_ID = 9L;
    private static final Long REQUEST_ID = 4L;

    @Mock private VerificationRequestRepository requestRepository;
    @Mock private VerificationDocumentRepository documentRepository;
    @Mock private StorageService storageService;
    @Mock private UserService userService;
    @Mock private ApplicationEventPublisher events;

    private VerificationService verificationService;

    @BeforeEach
    void setUp() {
        verificationService =
                new VerificationService(
                        requestRepository, documentRepository, storageService, userService, events);
    }

    private User user(Long id, boolean verified) {
        User user = new User("a@b.com", "A B", AuthProvider.GOOGLE, "sub-" + id);
        ReflectionTestUtils.setField(user, "id", id);
        if (verified) {
            user.markVerified();
        }
        return user;
    }

    private VerificationRequest pendingRequest() {
        VerificationRequest request =
                new VerificationRequest(user(USER_ID, false), VerificationType.INDIVIDUAL, null);
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        return request;
    }

    private void givenRequest(VerificationRequest request) {
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
    }

    private void givenNoDocuments() {
        when(documentRepository.findByVerificationRequestIdOrderByUploadedAtAsc(REQUEST_ID))
                .thenReturn(List.of());
    }

    private static MultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, null, content);
    }

    private static byte[] pngBytes() {
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] out = new byte[64];
        System.arraycopy(header, 0, out, 0, header.length);
        return out;
    }

    // --- Upload validation -----------------------------------------------------------

    @Test
    void storesARealImage() {
        VerificationRequest request = pendingRequest();
        givenRequest(request);
        givenNoDocuments();
        when(storageService.store(any(), any())).thenReturn("2026/08/19/abc.png");

        verificationService.uploadDocument(
                USER_ID, REQUEST_ID, DocType.GOVERNMENT_ID, file("id.png", pngBytes()));

        verify(documentRepository).save(any(VerificationDocument.class));
    }

    /** The exact file that got through before validation existed. */
    @Test
    void rejectsATextFileDisguisedAsAnImage() {
        givenRequest(pendingRequest());

        assertThatThrownBy(
                        () ->
                                verificationService.uploadDocument(
                                        USER_ID,
                                        REQUEST_ID,
                                        DocType.GOVERNMENT_ID,
                                        file(
                                                "id.png",
                                                "fake government id"
                                                        .getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VALIDATION_ERROR);

        verify(storageService, never()).store(any(), any());
        verify(documentRepository, never()).save(any());
    }

    /** The stored name comes from the detected format, never from what the client called it. */
    @Test
    void storedExtensionFollowsTheContentNotTheFilename() {
        givenRequest(pendingRequest());
        givenNoDocuments();
        when(storageService.store(any(), any())).thenReturn("2026/08/19/abc.pdf");

        verificationService.uploadDocument(
                USER_ID,
                REQUEST_ID,
                DocType.BUSINESS_REG_CERT,
                file("certificate.png", "%PDF-1.7 body".getBytes(StandardCharsets.UTF_8)));

        verify(storageService).store(eq("document.pdf"), any());
    }

    @Test
    void rejectsAnEmptyUpload() {
        assertThatThrownBy(
                        () ->
                                verificationService.uploadDocument(
                                        USER_ID,
                                        REQUEST_ID,
                                        DocType.GOVERNMENT_ID,
                                        file("id.png", new byte[0])))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VALIDATION_ERROR);
    }

    @Test
    void cannotUploadToAnAlreadyReviewedRequest() {
        VerificationRequest request = pendingRequest();
        request.approve(user(ADMIN_ID, false));
        givenRequest(request);

        assertThatThrownBy(
                        () ->
                                verificationService.uploadDocument(
                                        USER_ID,
                                        REQUEST_ID,
                                        DocType.GOVERNMENT_ID,
                                        file("id.png", pngBytes())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
    }

    /** Someone else's request answers 404, not 403 — a 403 would confirm it exists. */
    @Test
    void cannotUploadToSomeoneElsesRequest() {
        givenRequest(pendingRequest());

        assertThatThrownBy(
                        () ->
                                verificationService.uploadDocument(
                                        OTHER_USER_ID,
                                        REQUEST_ID,
                                        DocType.GOVERNMENT_ID,
                                        file("id.png", pngBytes())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
    }

    // --- Submission rules ------------------------------------------------------------

    @Test
    void anAlreadyVerifiedUserCannotSubmitAgain() {
        when(userService.requireById(USER_ID)).thenReturn(user(USER_ID, true));

        assertThatThrownBy(
                        () ->
                                verificationService.submit(
                                        USER_ID,
                                        new SubmitVerificationRequest(
                                                VerificationType.INDIVIDUAL, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
    }

    @Test
    void businessVerificationRequiresARegistrationNumber() {
        when(userService.requireById(USER_ID)).thenReturn(user(USER_ID, false));
        when(requestRepository.findByUserIdAndStatus(USER_ID, VerificationStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                verificationService.submit(
                                        USER_ID,
                                        new SubmitVerificationRequest(
                                                VerificationType.BUSINESS, "  ")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VALIDATION_ERROR);
    }

    /** A registration number means nothing for an individual, so it is dropped rather than kept. */
    @Test
    void individualVerificationDiscardsAnyRegistrationNumber() {
        when(userService.requireById(USER_ID)).thenReturn(user(USER_ID, false));
        when(requestRepository.findByUserIdAndStatus(USER_ID, VerificationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(requestRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0, VerificationRequest.class));

        var response =
                verificationService.submit(
                        USER_ID,
                        new SubmitVerificationRequest(VerificationType.INDIVIDUAL, "RC12345"));

        assertThat(response.registrationNumber()).isNull();
    }

    // --- Review ----------------------------------------------------------------------

    @Test
    void approvalPromotesTheUserToAVerifiedCreator() {
        VerificationRequest request = pendingRequest();
        givenRequest(request);
        givenNoDocuments();
        when(userService.requireById(ADMIN_ID)).thenReturn(user(ADMIN_ID, false));

        verificationService.approve(ADMIN_ID, REQUEST_ID);

        assertThat(request.getStatus()).isEqualTo(VerificationStatus.APPROVED);
        verify(userService).markVerified(USER_ID);
    }

    @Test
    void approvalEmailsTheApplicant() {
        VerificationRequest request = pendingRequest();
        givenRequest(request);
        when(userService.requireById(ADMIN_ID)).thenReturn(user(ADMIN_ID, false));

        verificationService.approve(ADMIN_ID, REQUEST_ID);

        NotificationEvent.VerificationReviewed event = capturePublished();
        assertThat(event.approved()).isTrue();
        assertThat(event.recipient().userId()).isEqualTo(USER_ID);
        assertThat(event.recipient().email()).isEqualTo("a@b.com");
    }

    @Test
    void rejectionEmailsTheApplicantWithTheReason() {
        VerificationRequest request = pendingRequest();
        givenRequest(request);
        when(userService.requireById(ADMIN_ID)).thenReturn(user(ADMIN_ID, false));

        verificationService.reject(ADMIN_ID, REQUEST_ID, "Blurry photo");

        NotificationEvent.VerificationReviewed event = capturePublished();
        assertThat(event.approved()).isFalse();
        assertThat(event.reason()).isEqualTo("Blurry photo");
    }

    private NotificationEvent.VerificationReviewed capturePublished() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        return (NotificationEvent.VerificationReviewed) captor.getValue();
    }

    @Test
    void anAlreadyDecidedRequestCannotBeReviewedTwice() {
        VerificationRequest request = pendingRequest();
        request.reject(user(ADMIN_ID, false), "Blurry photo");
        givenRequest(request);
        when(userService.requireById(ADMIN_ID)).thenReturn(user(ADMIN_ID, false));

        assertThatThrownBy(() -> verificationService.approve(ADMIN_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
    }
}
