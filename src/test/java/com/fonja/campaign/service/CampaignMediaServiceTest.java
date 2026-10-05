package com.fonja.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.campaign.dto.MediaUploadResponse;
import com.fonja.campaign.entity.CampaignMedia;
import com.fonja.campaign.repository.CampaignMediaRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import com.fonja.verification.service.StorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Anything served from /media lands in a browser as an image, so what gets in is the whole test.
 */
@ExtendWith(MockitoExtension.class)
class CampaignMediaServiceTest {

    private static final Long USER_ID = 3L;

    @Mock private CampaignMediaRepository mediaRepository;
    @Mock private StorageService storageService;
    @Mock private UserService userService;

    private CampaignMediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaService = new CampaignMediaService(mediaRepository, storageService, userService);
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/png", bytes);
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1};
    }

    @Test
    void storesAnImageAndReturnsItsPublicUrl() {
        User owner = new User("a@b.com", "A B", AuthProvider.GOOGLE, "sub");
        when(userService.requireById(USER_ID)).thenReturn(owner);
        when(storageService.store(eq("image.png"), any())).thenReturn("2026/09/17/abc.png");
        when(mediaRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            CampaignMedia media = invocation.getArgument(0);
                            ReflectionTestUtils.setField(media, "id", 42L);
                            return media;
                        });

        MediaUploadResponse response = mediaService.upload(USER_ID, file("cover.png", png()));

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.url()).isEqualTo("/api/v1/media/42");
        assertThat(response.contentType()).isEqualTo("image/png");
    }

    @Test
    void rejectsAPdfEvenThoughDocumentsAcceptIt() {
        MockMultipartFile pdf = file("cover.png", "%PDF-1.7 ...".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> mediaService.upload(USER_ID, pdf))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VALIDATION_ERROR);
        verify(storageService, never()).store(any(), any());
    }

    @Test
    void rejectsAFileWhoseBytesAreNotAnImageWhateverItIsCalled() {
        MockMultipartFile fake = file("cover.png", "<html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> mediaService.upload(USER_ID, fake))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void servesOnlyWhatHasARow() {
        when(mediaRepository.findById(9L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> mediaService.open(9L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
        verify(storageService, never()).retrieve(any());
    }

    @Test
    void openStreamsTheStoredFileWithItsRecordedType() {
        User owner = new User("a@b.com", "A B", AuthProvider.GOOGLE, "sub");
        CampaignMedia media = new CampaignMedia(owner, "2026/09/17/abc.png", "image/png");
        when(mediaRepository.findById(9L)).thenReturn(java.util.Optional.of(media));
        when(storageService.retrieve("2026/09/17/abc.png"))
                .thenReturn(new ByteArrayInputStream(png()));

        CampaignMediaService.StoredMedia stored = mediaService.open(9L);

        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(stored.content()).isNotNull();
    }
}
