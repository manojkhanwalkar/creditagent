package com.creditagent.web.auth.otp;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Texts sign-in codes through Amazon SNS, published straight to the phone number (no topic). Marked
 * {@code Transactional}, so SNS routes it for reliability rather than cost and does not apply
 * marketing opt-out rules meant for promotions.
 */
public final class SnsSmsOtpSender implements OtpSender {

    static final String SMS_TYPE = "AWS.SNS.SMS.SMSType";
    static final String SENDER_ID = "AWS.SNS.SMS.SenderID";
    static final String ORIGINATION_NUMBER = "AWS.MM.SMS.OriginationNumber";

    private final SnsClient sns;
    private final Map<String, MessageAttributeValue> attributes;

    /**
     * @param senderId alphanumeric sender id where countries allow one, or null/blank
     * @param originationNumber the registered number (toll-free, 10DLC or short code) to send from, or
     *     null/blank for the account default; US carriers require a registered one
     */
    public SnsSmsOtpSender(SnsClient sns, String senderId, String originationNumber) {
        this.sns = Objects.requireNonNull(sns);
        Map<String, MessageAttributeValue> values = new HashMap<>();
        values.put(SMS_TYPE, string("Transactional"));
        if (senderId != null && !senderId.isBlank()) {
            values.put(SENDER_ID, string(senderId));
        }
        if (originationNumber != null && !originationNumber.isBlank()) {
            values.put(ORIGINATION_NUMBER, string(originationNumber));
        }
        this.attributes = Map.copyOf(values);
    }

    @Override
    public void send(String destination, String code, Duration validFor) {
        PublishRequest request = PublishRequest.builder()
                .phoneNumber(destination)
                .message(OtpSender.text(code, validFor, true))
                .messageAttributes(attributes)
                .build();
        try {
            sns.publish(request);
        } catch (SdkException e) {
            throw new OtpDeliveryException(OtpChannel.SMS, e);
        }
    }

    private static MessageAttributeValue string(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
