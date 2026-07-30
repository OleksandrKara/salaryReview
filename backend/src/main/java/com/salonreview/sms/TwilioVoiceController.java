package com.salonreview.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Twilio's inbound-Voice webhook for the salon's toll-free number (+18449814613) — returns a
 * fixed TwiML {@code <Dial>} forwarding every call to the salon's Ooma line, since calls are
 * still answered there, not through this app (see
 * openspec/changes/lead-followup-and-manager-inbox tasks.md section 6). No signature check,
 * unlike the inbound-SMS webhook: the response never varies with the caller or request, so
 * there's nothing sensitive a forged request could extract or change — same "harmless public
 * endpoint" reasoning {@link com.salonreview.config.SecurityConfig} already applies to
 * {@code /r/**}. Twilio's own Voice webhook default method is POST, but GET is accepted too in
 * case the number is ever configured either way.
 */
@RestController
public class TwilioVoiceController {

    private static final Logger log = LoggerFactory.getLogger(TwilioVoiceController.class);

    private static final String FORWARD_TO_OOMA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response><Dial>+16193231185</Dial></Response>
            """;

    @RequestMapping(value = "/api/public/voice/inbound", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> inbound() {
        log.info("Inbound call to toll-free number — forwarding to Ooma");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(FORWARD_TO_OOMA);
    }
}
