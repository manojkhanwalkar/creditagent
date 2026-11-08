package com.creditagent.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.common.Ssn;
import com.creditagent.kyc.Verification.Decision;
import com.creditagent.kyc.Verification.Reason;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SsnDocumentVerifierTest {

    static final KycProperties DEFAULTS = new KycProperties(100, 10 * 1024 * 1024, 40_000_000, 600, 360, true);

    final SsnDocumentVerifier verifier = new SsnDocumentVerifier(DEFAULTS);

    @Test
    void aSharpCardIsVerifiedAndItsSsnRead() {
        Verification result = verifier.verify(SsnCardRenderer.render(Ssn.parse("123456789"), 0));

        assertThat(result.decision()).isEqualTo(Decision.VERIFIED);
        assertThat(result.ssn()).isEqualTo("123-45-6789");
        assertThat(result.verificationId()).matches("kyc_[0-9a-f]{32}");
        assertThat(result.reason()).isNull();
        assertThat(result.toString()).doesNotContain("6789");
    }

    @Test
    void slightBlurIsTolerated() {
        assertThat(verifier.verify(SsnCardRenderer.render(Ssn.parse("123-45-6789"), 1)).decision())
                .isEqualTo(Decision.VERIFIED);
    }

    @Test
    void aBlurredCardIsRejectedAsBlurry() {
        for (int radius : new int[] {3, 4, 8}) {
            Verification result = verifier.verify(SsnCardRenderer.render(Ssn.parse("123-45-6789"), radius));
            assertThat(result.decision()).as("blur %d", radius).isEqualTo(Decision.REJECTED);
            assertThat(result.reason()).isEqualTo(Reason.IMAGE_BLURRY);
            assertThat(result.message()).contains("blurry").contains("try again");
            assertThat(result.ssn()).isNull();
        }
    }

    @Test
    void somethingThatIsNotAnImageIsUnsupported() {
        assertThat(verifier.verify("hello, I am a text file".getBytes(StandardCharsets.UTF_8)).reason())
                .isEqualTo(Reason.UNSUPPORTED_IMAGE);
        assertThat(verifier.verify(new byte[0]).reason()).isEqualTo(Reason.UNSUPPORTED_IMAGE);
    }

    @Test
    void aTruncatedPngIsUnsupported() {
        byte[] card = SsnCardRenderer.render(Ssn.parse("123-45-6789"), 0);
        assertThat(verifier.verify(Arrays.copyOf(card, 200)).reason()).isEqualTo(Reason.UNSUPPORTED_IMAGE);
    }

    @Test
    void anImageWithoutTheCardLabelIsNotAnSsnCard() {
        assertThat(verifier.verify(SsnCardRenderer.render("123-45-6789", null, 0)).reason())
                .isEqualTo(Reason.NOT_AN_SSN_CARD);
        assertThat(verifier.verify(SsnCardRenderer.render("123-45-6789",
                        SsnCardRenderer.label("DRIVER_LICENSE", "123-45-6789"), 0)).reason())
                .isEqualTo(Reason.NOT_AN_SSN_CARD);
    }

    @Test
    void aCardWithAnInvalidOrMissingNumberIsUnreadable() {
        assertThat(verifier.verify(SsnCardRenderer.render("000-12-3456",
                        SsnCardRenderer.label(SsnDocumentVerifier.SSN_CARD, "000-12-3456"), 0)).reason())
                .isEqualTo(Reason.SSN_UNREADABLE);
        assertThat(verifier.verify(SsnCardRenderer.render("",
                        SsnCardRenderer.label(SsnDocumentVerifier.SSN_CARD, null), 0)).reason())
                .isEqualTo(Reason.SSN_UNREADABLE);
    }

    @Test
    void jpegIsAcceptedToo() throws IOException {
        BufferedImage card = ImageIO.read(new ByteArrayInputStream(SsnCardRenderer.render(Ssn.parse("123-45-6789"), 0)));
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(card, "jpeg", jpeg);
        // A JPEG written this way has no label, so it decodes and passes the blur check but is not a card.
        assertThat(verifier.verify(jpeg.toByteArray()).reason()).isEqualTo(Reason.NOT_AN_SSN_CARD);
    }

    @Test
    void tooSmallAndTooManyPixelsAreRefusedBeforeDecoding() throws IOException {
        BufferedImage tiny = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(tiny, "png", png);
        assertThat(verifier.verify(png.toByteArray()).reason()).isEqualTo(Reason.IMAGE_SIZE);

        SsnDocumentVerifier strict = new SsnDocumentVerifier(new KycProperties(100, 10_000_000, 100_000, 600, 360, false));
        assertThat(strict.verify(SsnCardRenderer.render(Ssn.parse("123-45-6789"), 0)).reason())
                .isEqualTo(Reason.IMAGE_SIZE);
    }

    @Test
    void aFileOverTheByteLimitIsRefused() {
        SsnDocumentVerifier strict = new SsnDocumentVerifier(new KycProperties(100, 1_000, 40_000_000, 600, 360, false));
        assertThat(strict.verify(SsnCardRenderer.render(Ssn.parse("123-45-6789"), 0)).reason())
                .isEqualTo(Reason.IMAGE_SIZE);
    }

    @Test
    void sharpnessFallsAsBlurRises() throws IOException {
        double previous = Double.MAX_VALUE;
        for (int radius = 0; radius <= 6; radius++) {
            double score = BlurDetector.sharpness(
                    ImageIO.read(new ByteArrayInputStream(SsnCardRenderer.render(Ssn.parse("123-45-6789"), radius))));
            assertThat(score).as("blur %d", radius).isLessThan(previous);
            previous = score;
        }
    }

    @Test
    void largeImagesAreScaledBeforeScoring() {
        BufferedImage wide = new BufferedImage(4000, 2500, BufferedImage.TYPE_INT_RGB);
        assertThat(BlurDetector.sharpness(wide)).isZero();
    }
}
