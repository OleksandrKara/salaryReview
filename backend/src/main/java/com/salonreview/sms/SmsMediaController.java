package com.salonreview.sms;

import com.salonreview.domain.SmsMessageMedia;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a stored MMS photo by its opaque {@link SmsMessageMedia#getAccessToken()} — unauthenticated
 * ({@code permitAll()} in {@link com.salonreview.config.SecurityConfig}), same shape as the
 * click-tracked {@code /r/{token}} short link: no session/auth header, so both the manager
 * dashboard's plain {@code <img src>} tags and Twilio's own outbound-media-fetch requests (see
 * {@code TwilioSmsClient#send} with {@code mediaUrls}) can retrieve it. Not enumerable — the token
 * is a {@link ClickTokens}-generated random string, not the row's own id.
 */
@RestController
public class SmsMediaController {

    private final SmsMediaService mediaService;

    public SmsMediaController(SmsMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping("/api/public/sms-media/{token}")
    public ResponseEntity<byte[]> get(@PathVariable String token) {
        return mediaService.get(token)
                .map(m -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(m.getContentType()))
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                        .body(m.getFileData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
