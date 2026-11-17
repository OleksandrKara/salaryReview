package com.salonreview.intake;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The client-facing form. The token in the link is the only credential; answers are never echoed back. */
@RestController
@RequestMapping("/api/public/intake")
public class IntakePublicController {

    private static final long WINDOW_MS = 10 * 60_000L;
    private static final int MAX_REQUESTS = 40;

    private final IntakeFormService service;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public IntakePublicController(IntakeFormService service) {
        this.service = service;
    }

    public record SubmitRequest(Map<String, String> answers, String signatureName, boolean agreed) {}

    @GetMapping("/{token}")
    public ResponseEntity<?> view(@PathVariable String token, HttpServletRequest req) {
        if (limited(req)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return service.openForClient(token)
                .<ResponseEntity<?>>map(v -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{token}")
    public ResponseEntity<?> submit(@PathVariable String token, @RequestBody SubmitRequest body,
                                    HttpServletRequest req) {
        if (limited(req)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        try {
            return switch (service.submit(token, body.answers(), body.signatureName(), body.agreed())) {
                case OK -> ResponseEntity.ok(Map.of("status", "SIGNED"));
                case ALREADY_SIGNED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "SIGNED"));
                case NOT_FOUND -> ResponseEntity.notFound().build();
            };
        } catch (IntakeAnswers.Invalid e) {
            return ResponseEntity.badRequest().body(Map.of("field", e.getMessage()));
        }
    }

    private boolean limited(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= MAX_REQUESTS) {
                return true;
            }
            q.addLast(now);
        }
        if (hits.size() > 20_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() > WINDOW_MS);
        }
        return false;
    }
}
