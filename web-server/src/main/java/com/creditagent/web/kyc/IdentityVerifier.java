package com.creditagent.web.kyc;

import com.creditagent.common.Ssn;

/**
 * The KYC provider, as registration sees it: a photo of a Social Security card in, the SSN it
 * proves out. The user never types their SSN; it is only ever what the provider read from the card.
 */
public interface IdentityVerifier {

    /** An SSN the provider read from an authentic card, and the provider's reference for the check. */
    record VerifiedSsn(Ssn ssn, String verificationId) {
        @Override
        public String toString() {
            return "VerifiedSsn[ssn=" + ssn.masked() + ", verificationId=" + verificationId + "]";
        }
    }

    /**
     * @param image the uploaded file's bytes
     * @throws DocumentRejectedException if the provider refused the image (blurred, not a card, …)
     * @throws com.creditagent.clients.ServiceUnavailableException if the provider failed or could not be reached
     */
    VerifiedSsn verifySsnCard(byte[] image);
}
