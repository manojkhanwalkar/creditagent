package com.creditagent.web.auth;

import com.creditagent.common.Ssn;
import com.creditagent.web.kyc.DocumentRejectedException;
import com.creditagent.web.kyc.IdentityVerifier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A KYC provider for tests. An "image" is text: {@code card:123-45-6789} is a sharp card with that
 * SSN, {@code blurry} is a blurred photo, anything else is not a card.
 */
final class FakeIdentityVerifier implements IdentityVerifier {

    final AtomicInteger calls = new AtomicInteger();

    static byte[] card(String ssn) {
        return ("card:" + ssn).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] blurry() {
        return "blurry".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public VerifiedSsn verifySsnCard(byte[] image) {
        calls.incrementAndGet();
        String text = new String(image, StandardCharsets.UTF_8);
        if (text.equals("blurry")) {
            throw new DocumentRejectedException(DocumentRejectedException.IMAGE_BLURRY);
        }
        if (!text.startsWith("card:")) {
            throw new DocumentRejectedException("NOT_AN_SSN_CARD");
        }
        return new VerifiedSsn(Ssn.parse(text.substring(5)), "kyc_test_" + calls.get());
    }
}
