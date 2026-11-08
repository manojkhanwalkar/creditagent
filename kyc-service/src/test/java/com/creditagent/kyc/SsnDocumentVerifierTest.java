package com.creditagent.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.common.Ssn;
import com.creditagent.kyc.Verification.Decision;
import com.creditagent.kyc.Verification.Reason;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
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

    @Test
    void largeImagesAreDecodedSubsampled() throws Exception {
        assertThat(DocumentImage.subsampling(856, 540)).isEqualTo(1);
        assertThat(DocumentImage.subsampling(2000, 2000)).isEqualTo(1);
        assertThat(DocumentImage.subsampling(4000, 3000)).isEqualTo(2);
        assertThat(DocumentImage.subsampling(6300, 6300)).isEqualTo(4);
        assertThat(DocumentImage.subsampling(360, 110_000)).isEqualTo(4);

        // About 40 KB on disk, 40 megapixels: decoded in full it would be 40 MB of grey (and 320 MB
        // as 16-bit RGBA). It must come out no bigger than MAX_DECODED_PIXELS.
        byte[] png = greyPng(6300, 6300);
        DocumentImage image = DocumentImage.decode(png, DEFAULTS);
        assertThat((long) image.pixels().getWidth() * image.pixels().getHeight())
                .isLessThanOrEqualTo(DocumentImage.MAX_DECODED_PIXELS);
        assertThat(verifier.verify(png).reason()).isEqualTo(Reason.IMAGE_BLURRY);
    }

    @Test
    void aLargeSharpCardIsStillVerified() throws Exception {
        BufferedImage card = ImageIO.read(new ByteArrayInputStream(SsnCardRenderer.render(Ssn.parse("123-45-6789"), 0)));
        BufferedImage large = new BufferedImage(card.getWidth() * 5, card.getHeight() * 5, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = large.createGraphics();
        g.drawImage(card, 0, 0, large.getWidth(), large.getHeight(), null);
        g.dispose();
        assertThat(BlurDetector.sharpness(DocumentImage.decode(png(large), DEFAULTS).pixels()))
                .isGreaterThan(DEFAULTS.minSharpness());
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** An 8-bit grey PNG of zeros, written by hand so the test never holds the full bitmap. */
    private static byte[] greyPng(int width, int height) throws IOException {
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(idat)) {
            byte[] row = new byte[width + 1]; // filter byte 0, then the pixels
            for (int y = 0; y < height; y++) {
                deflate.write(row);
            }
        }
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(ihdr);
        header.writeInt(width);
        header.writeInt(height);
        header.write(new byte[] {8, 0, 0, 0, 0}); // 8-bit grey, deflate, adaptive filter, no interlace
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(png, "IHDR", ihdr.toByteArray());
        chunk(png, "IDAT", idat.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream png, String type, byte[] data) throws IOException {
        DataOutputStream out = new DataOutputStream(png);
        out.writeInt(data.length);
        out.writeBytes(type);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(type.getBytes(StandardCharsets.US_ASCII));
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
