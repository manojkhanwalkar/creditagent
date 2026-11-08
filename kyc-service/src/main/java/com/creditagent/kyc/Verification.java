package com.creditagent.kyc;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The provider's answer for one document. A document problem is a normal answer ({@code REJECTED}
 * with a reason), not an HTTP error, as with most identity-verification APIs.
 *
 * @param ssn the SSN read from the card, {@code AAA-GG-SSSS}; only when {@code VERIFIED}
 * @param verificationId the provider's reference for this check; only when {@code VERIFIED}
 * @param message safe to show to the person who uploaded the image
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Verification(Decision decision, Reason reason, String message, String ssn, String verificationId) {

    public enum Decision { VERIFIED, REJECTED }

    public enum Reason {
        /** The image is too blurred to read. The user should take a sharper photo. */
        IMAGE_BLURRY("The image is too blurry to read. Take a sharper photo in good light and try again."),
        /** Not a PNG or JPEG, or the file is damaged. */
        UNSUPPORTED_IMAGE("Upload a PNG or JPEG photo of your Social Security card."),
        /** Too few pixels to read, or too many to process. */
        IMAGE_SIZE("The image is too small to read or too large to process. Upload an ordinary photo of the whole card."),
        /** A readable image, but not a Social Security card. */
        NOT_AN_SSN_CARD("That does not look like a Social Security card. Upload a photo of the front of your card."),
        /** A card, but no valid SSN could be read from it. */
        SSN_UNREADABLE("The number on the card could not be read. Upload a clearer photo of the whole card.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    static Verification verified(String ssn, String verificationId) {
        return new Verification(Decision.VERIFIED, null, null, ssn, verificationId);
    }

    static Verification rejected(Reason reason) {
        return new Verification(Decision.REJECTED, reason, reason.message(), null, null);
    }

    @Override
    public String toString() {
        return "Verification[decision=" + decision + ", reason=" + reason + ", ssn=<redacted>]";
    }
}
