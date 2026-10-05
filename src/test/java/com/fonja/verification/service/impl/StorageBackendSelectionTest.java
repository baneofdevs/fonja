package com.fonja.verification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fonja.verification.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Builds the storage beans through Spring rather than by hand, so wiring mistakes — a missing
 * property, two constructors with neither marked — fail here instead of at deploy time. Building
 * the R2 client makes no network call, so dummy credentials are enough.
 */
class StorageBackendSelectionTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            LocalStorageServiceImpl.class, R2StorageServiceImpl.class);

    @Test
    void localDiskIsTheDefault() {
        context.withPropertyValues("fonja.storage.local.base-path=target/storage-test")
                .run(
                        ctx ->
                                assertThat(ctx)
                                        .hasNotFailed()
                                        .getBean(StorageService.class)
                                        .isInstanceOf(LocalStorageServiceImpl.class));
    }

    @Test
    void r2IsSelectedByTypeAndNeedsNoLocalPath() {
        context.withPropertyValues(
                        "fonja.storage.type=r2",
                        "fonja.storage.r2.account-id=0123456789abcdef",
                        "fonja.storage.r2.access-key-id=key",
                        "fonja.storage.r2.secret-access-key=secret",
                        "fonja.storage.r2.bucket=fonja-uploads")
                .run(
                        ctx ->
                                assertThat(ctx)
                                        .hasNotFailed()
                                        .getBean(StorageService.class)
                                        .isInstanceOf(R2StorageServiceImpl.class));
    }
}
