package com.creditagent.web.kyc;

import java.util.Map;

/**
 * 422: the KYC provider refused the SSN card image. {@link #reason()} tells the UI what to do;
 * {@code IMAGE_BLURRY} means "take a sharper photo and try again". The message is safe to show.
 */
public class DocumentRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String IMAGE_BLURRY = "IMAGE_BLURRY";
    public static final String UNSUPPORTED_IMAGE = "UNSUPPORTED_IMAGE";
    public static final String DOCUMENT_REJECTED = "DOCUMENT_REJECTED";

    /** What the user is told for each reason the provider gives. Unknown reasons get the generic one. */
    private static final Map<String, String> MESSAGES = Map.of(
            IMAGE_BLURRY,
            "The image is too blurry to read. Take a sharper photo of your card in good light and upload it again.",
            UNSUPPORTED_IMAGE, "Upload a PNG or JPEG photo of your Social Security card.",
            "IMAGE_SIZE", "The image is too small to read or too large to process. Upload an ordinary photo of the whole card.",
            "NOT_AN_SSN_CARD", "That does not look like a Social Security card. Upload a photo of the front of your card.",
            "SSN_UNREADABLE", "The number on the card could not be read. Upload a clearer photo of the whole card.",
            DOCUMENT_REJECTED, "Your Social Security card could not be verified. Upload a clear photo of the front of the card.");

    private final String reason;

    public DocumentRejectedException(String reason) {
        this(reason, null);
    }

    public DocumentRejectedException(String reason, Throwable cause) {
        super(MESSAGES.get(known(reason)), cause);
        this.reason = known(reason);
    }

    /** {@code reason} if it has a message, else {@code DOCUMENT_REJECTED}. Map.of rejects a null key. */
    private static String known(String reason) {
        return reason != null && MESSAGES.containsKey(reason) ? reason : DOCUMENT_REJECTED;
    }

    /** A known reason code, or {@code DOCUMENT_REJECTED}. */
    public String reason() {
        return reason;
    }
}
