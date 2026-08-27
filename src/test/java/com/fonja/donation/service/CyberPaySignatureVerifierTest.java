package com.fonja.donation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.donation.config.CyberPayConfig;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CyberPaySignatureVerifierTest {

    private static final String KEY = "fonja-test-integration-key";

    private static final String BODY =
            "{\"Code\":\"PY00\",\"Data\":{\"Reference\":\"KIN190523841971254995\","
                    + "\"PaymentChannel\":\"BankTransfer\"}}";

    /**
     * Computed by an independent implementation (Python's {@code hmac.new(key, body,
     * hashlib.sha512).hexdigest()}), not by this class. A test that signs with the same code it
     * verifies with would pass just as happily against a wrong algorithm.
     */
    private static final String EXPECTED_SIGNATURE =
            "83475b7d9b587f6db00e3faf4511a677de15f40d0d362ac9c0ece7d83a939156"
                    + "c42d1d5694f47a08781e39dab613921a113580c0cad2fdb26894d7b2a2373613";

    private CyberPayConfig config;
    private CyberPaySignatureVerifier verifier;

    private static byte[] body() {
        return BODY.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        config = new CyberPayConfig();
        config.setIntegrationKey(KEY);
        verifier = new CyberPaySignatureVerifier(config);
    }

    @Test
    void acceptsASignatureMatchingCyberPaysDocumentedScheme() {
        assertThatCode(() -> verifier.verify(body(), EXPECTED_SIGNATURE))
                .doesNotThrowAnyException();
    }

    @Test
    void digestMatchesAnIndependentImplementation() {
        assertThat(CyberPaySignatureVerifier.hexDigest(body(), KEY)).isEqualTo(EXPECTED_SIGNATURE);
    }

    @Test
    void acceptsUppercaseHexBecauseTheEncodingIsNotPartOfTheSecret() {
        assertThatCode(() -> verifier.verify(body(), EXPECTED_SIGNATURE.toUpperCase(Locale.ROOT)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsABodyAlteredByASingleByte() {
        // The whole point of signing: a forger who flips "Successful" to something else, or the
        // amount to something larger, invalidates the digest.
        byte[] tampered = BODY.replace("PY00", "PY01").getBytes(StandardCharsets.UTF_8);

        assertUnauthorized(() -> verifier.verify(tampered, EXPECTED_SIGNATURE));
    }

    @Test
    void rejectsASignatureFromADifferentKey() {
        String forged = CyberPaySignatureVerifier.hexDigest(body(), "not-our-integration-key");

        assertUnauthorized(() -> verifier.verify(body(), forged));
    }

    @Test
    void rejectsAMissingSignatureHeader() {
        assertUnauthorized(() -> verifier.verify(body(), null));
        assertUnauthorized(() -> verifier.verify(body(), "   "));
    }

    @Test
    void rejectsAnEmptyBody() {
        assertUnauthorized(() -> verifier.verify(new byte[0], EXPECTED_SIGNATURE));
        assertUnauthorized(() -> verifier.verify(null, EXPECTED_SIGNATURE));
    }

    @Test
    void failsClosedWhenNoIntegrationKeyIsConfigured() {
        config.setIntegrationKey("");

        // 503, not 401: an unconfigured key is our fault, and CyberPay's 72 hours of retries are
        // the window in which we can fix it without losing the payment.
        assertThatThrownBy(() -> verifier.verify(body(), EXPECTED_SIGNATURE))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.GATEWAY_UNAVAILABLE);
    }

    private void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.UNAUTHORIZED);
    }
}
