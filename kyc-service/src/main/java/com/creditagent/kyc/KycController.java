package com.creditagent.kyc;

import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /api/v1/ssn-verifications}: a photo of a Social Security card in (multipart part
 * {@code document}), a {@link Verification} out. Document problems, a blurred photo among them, are
 * a 200 with {@code decision: REJECTED} and a reason; only a malformed request is an HTTP error.
 *
 * <p>An upload over the multipart limit is a 413 from Spring's problem-details handler. It cannot be
 * handled here: the multipart body is parsed, and the limit hit, before a handler is chosen, so a
 * controller's own {@code @ExceptionHandler} is never consulted (see {@code UploadLimitTest}).
 */
@RestController
@RequestMapping(path = "/api/v1")
class KycController {

    private final SsnDocumentVerifier verifier;
    private final KycProperties properties;

    KycController(SsnDocumentVerifier verifier, KycProperties properties) {
        this.verifier = verifier;
        this.properties = properties;
    }

    @PostMapping(path = "/ssn-verifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Verification> verify(@RequestPart("document") MultipartFile document) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(verifier.verify(document.getBytes()));
    }

    /**
     * A sample card to upload in a demo; {@code blur} 1–12 makes a blurred one. Off unless
     * {@code kyc.sample-cards-enabled} is set, since it makes a card for any SSN.
     */
    @GetMapping(path = "/sample-cards/{ssn}", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> sampleCard(@PathVariable String ssn, @RequestParam(defaultValue = "0") int blur) {
        if (!properties.sampleCardsEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (blur < 0 || blur > SsnCardRenderer.MAX_BLUR) {
            return ResponseEntity.badRequest().build();
        }
        Ssn parsed;
        try {
            parsed = Ssn.parse(ssn);
        } catch (InvalidSsnException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SsnCardRenderer.render(parsed, blur));
    }
}
