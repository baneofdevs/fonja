package com.fonja.verification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ExtendWith(MockitoExtension.class)
class R2StorageServiceImplTest {

    private static final String BUCKET = "fonja-uploads";

    @Mock private S3Client s3;

    private R2StorageServiceImpl storage;

    @BeforeEach
    void setUp() {
        storage = new R2StorageServiceImpl(s3, BUCKET);
    }

    @Test
    void storesUnderADatedKeyInTheBucketAndReturnsTheKey() throws Exception {
        byte[] bytes = "png bytes".getBytes(StandardCharsets.UTF_8);

        String reference = storage.store("image.PNG", new ByteArrayInputStream(bytes));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(reference);
        assertThat(reference).matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.png");
        try (InputStream sent = body.getValue().contentStreamProvider().newStream()) {
            assertThat(sent.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void anOddExtensionIsDropped() {
        String reference =
                storage.store("../../etc/passwd.s h", new ByteArrayInputStream(new byte[0]));

        assertThat(reference).matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}");
    }

    @Test
    void anUploadFailureIsAStorageError() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connection reset"));

        assertThatThrownBy(() -> storage.store("a.jpg", new ByteArrayInputStream(new byte[1])))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.STORAGE_ERROR);
    }

    @Test
    void retrievesTheObjectByItsKey() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(
                        new ResponseInputStream<>(
                                GetObjectResponse.builder().build(),
                                AbortableInputStream.create(new ByteArrayInputStream(bytes))));

        try (InputStream in = storage.retrieve("2026/10/05/abc.png")) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("2026/10/05/abc.png");
    }

    @Test
    void aMissingObjectIsNotFound() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertThatThrownBy(() -> storage.retrieve("2026/10/05/gone.png"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
    }
}
