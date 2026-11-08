package com.creditagent.kyc;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param minSharpness images whose sharpness score (variance of the Laplacian) is below this are
 *     refused as blurred; see {@link BlurDetector}
 * @param maxImageBytes largest upload accepted
 * @param maxPixels largest decoded image (width × height), checked from the header before decoding
 *     so a small file that expands to a huge bitmap is refused cheaply
 * @param minWidth smallest width, in pixels, that can hold a readable card
 * @param minHeight smallest height, in pixels, that can hold a readable card
 * @param sampleCardsEnabled serve demo SSN card images at {@code /api/v1/sample-cards/{ssn}}
 */
@Validated
@ConfigurationProperties("kyc")
public record KycProperties(
        @Positive double minSharpness,
        @Positive long maxImageBytes,
        @Positive long maxPixels,
        @Positive int minWidth,
        @Positive int minHeight,
        boolean sampleCardsEnabled) {}
