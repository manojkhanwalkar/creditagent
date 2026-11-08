package com.creditagent.kyc;

import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import com.creditagent.kyc.Verification.Reason;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dummy provider's checks, in the order a real one runs them:
 *
 * <ol>
 *   <li>the file is a PNG or JPEG of a sensible size;
 *   <li>the image is sharp enough to read ({@link BlurDetector});
 *   <li>the image is a Social Security card;
 *   <li>a valid SSN can be read from it.
 * </ol>
 *
 * <p><b>Stand-in for OCR.</b> A real provider reads the card with OCR and checks security features.
 * This one reads a machine-readable label that {@link SsnCardRenderer} writes into the image's
 * metadata, so the demo is deterministic and needs no OCR engine. Anyone can write that label, so
 * this proves nothing about identity: swap in a real provider before real use.
 */
public class SsnDocumentVerifier {

    private static final Logger log = LoggerFactory.getLogger(SsnDocumentVerifier.class);

    /** The label {@link SsnCardRenderer} writes, e.g. {@code CreditAgent-KYC-Demo/1 document=SSN_CARD ssn=123-45-6789}. */
    static final String LABEL_PREFIX = "CreditAgent-KYC-Demo/1";
    private static final Pattern LABEL = Pattern.compile(
            "^" + Pattern.quote(LABEL_PREFIX) + " document=(\\w+)(?: ssn=([0-9-]{9,11}))?$");
    static final String SSN_CARD = "SSN_CARD";

    private final KycProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SsnDocumentVerifier(KycProperties properties) {
        this.properties = properties;
    }

    public Verification verify(byte[] upload) {
        Verification result = check(upload);
        log.info("SSN document check: {}{}", result.decision(), result.reason() == null ? "" : " (" + result.reason() + ")");
        return result;
    }

    private Verification check(byte[] upload) {
        DocumentImage image;
        try {
            image = DocumentImage.decode(upload, properties);
        } catch (DocumentImage.Unreadable e) {
            return Verification.rejected(e.reason);
        }
        if (BlurDetector.sharpness(image.pixels()) < properties.minSharpness()) {
            return Verification.rejected(Reason.IMAGE_BLURRY);
        }
        Optional<Matcher> label = image.text().stream()
                .map(String::strip)
                .map(LABEL::matcher)
                .filter(Matcher::matches)
                .findFirst();
        if (label.isEmpty() || !SSN_CARD.equals(label.get().group(1))) {
            return Verification.rejected(Reason.NOT_AN_SSN_CARD);
        }
        String raw = label.get().group(2);
        Ssn ssn;
        try {
            ssn = Ssn.parse(raw);
        } catch (InvalidSsnException e) {
            return Verification.rejected(Reason.SSN_UNREADABLE);
        }
        String digits = ssn.digits();
        String formatted = digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
        return Verification.verified(formatted, verificationId());
    }

    private String verificationId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "kyc_" + HexFormat.of().formatHex(bytes);
    }
}
