package com.fonja.verification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Detection is by content alone, so these fixtures are raw signatures rather than real files. */
class DocumentFormatTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void detectsJpeg() {
        assertThat(DocumentFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)))
                .contains(DocumentFormat.JPEG);
    }

    @Test
    void detectsPng() {
        assertThat(DocumentFormat.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)))
                .contains(DocumentFormat.PNG);
    }

    @Test
    void detectsWebpWhoseMarkerSitsAfterTheLengthField() {
        assertThat(
                        DocumentFormat.detect(
                                bytes(
                                        'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B',
                                        'P')))
                .contains(DocumentFormat.WEBP);
    }

    @Test
    void detectsPdf() {
        assertThat(DocumentFormat.detect("%PDF-1.7".getBytes(StandardCharsets.UTF_8)))
                .contains(DocumentFormat.PDF);
    }

    /** The exact case that got through before this existed: text content, image extension. */
    @Test
    void rejectsPlainText() {
        assertThat(DocumentFormat.detect("fake government id".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    void rejectsAnExecutable() {
        assertThat(DocumentFormat.detect(bytes(0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01))).isEmpty();
    }

    @Test
    void rejectsAFileTooShortToIdentify() {
        assertThat(DocumentFormat.detect(bytes(0xFF, 0xD8))).isEmpty();
    }

    @Test
    void rejectsEmptyAndNullHeaders() {
        assertThat(DocumentFormat.detect(new byte[0])).isEmpty();
        assertThat(DocumentFormat.detect(null)).isEmpty();
    }

    /** A RIFF container that is not WebP — a .wav, for instance — must not pass as an image. */
    @Test
    void rejectsRiffThatIsNotWebp() {
        assertThat(
                        DocumentFormat.detect(
                                bytes(
                                        'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'A', 'V',
                                        'E')))
                .isEmpty();
    }
}
