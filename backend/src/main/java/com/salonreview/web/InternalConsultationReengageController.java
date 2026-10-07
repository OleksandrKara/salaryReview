package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.sms.ConsultationReengageOneOffService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Host-side trigger for {@link ConsultationReengageOneOffService} (owner approved 2026-10-07), same
 * X-Internal-Api-Key gate as every /api/internal call. GET is a read-only preview; POST /test sends
 * one proof to an address; POST /send/{wave} is the real send and needs {@code confirm=SEND}. */
@RestController
@RequestMapping("/api/internal/one-off/consultation-reengage")
public class InternalConsultationReengageController {

    private final InternalApiProperties internalApi;
    private final ConsultationReengageOneOffService service;

    public InternalConsultationReengageController(InternalApiProperties internalApi, ConsultationReengageOneOffService service) {
        this.internalApi = internalApi;
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> preview(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key) throws Exception {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(service.preview());
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                                  @RequestParam String email, @RequestParam(defaultValue = "Anna") String artist,
                                  @RequestParam(defaultValue = "false") boolean ownerWrites) throws Exception {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        service.sendTest(email, artist, ownerWrites);
        return ResponseEntity.ok(Map.of("sent", email));
    }

    @PostMapping("/send/{wave}")
    public ResponseEntity<?> send(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                                  @PathVariable int wave, @RequestParam(required = false) String confirm) throws Exception {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        if (!"SEND".equals(confirm)) return ResponseEntity.badRequest().body(Map.of("error", "confirm=SEND required"));
        return ResponseEntity.ok(service.send(wave));
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
