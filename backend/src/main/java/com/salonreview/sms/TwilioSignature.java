package com.salonreview.sms;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Twilio's webhook signature scheme, shared by every controller that receives an unauthenticated
 * POST from Twilio ({@link TwilioInboundSmsController}, {@link TwilioStatusCallbackController}):
 * {@code X-Twilio-Signature} = base64(HMAC-SHA1(authToken, webhookUrl +
 * sorted-concatenated "key"+"value" pairs of every POST param)).
 */
final class TwilioSignature {

    private TwilioSignature() {
    }

    static boolean valid(String authToken, String webhookUrl, Map<String, String> params, String signature) {
        if (signature == null || signature.isBlank() || authToken == null || authToken.isBlank()) {
            return false;
        }
        try {
            StringBuilder data = new StringBuilder(webhookUrl);
            params.keySet().stream().sorted().forEach(key -> data.append(key).append(params.get(key)));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] computed = mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(computed);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
