package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.square.RedoService;
import com.salonreview.square.RedoService.CreateRequest;
import com.salonreview.square.RedoService.RedoView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Redos (owner/manager — gated in SecurityConfig). Recording a redo moves a service's commission from
 * the original provider to the redo provider.
 */
@RestController
@RequestMapping("/api/redos")
public class RedoController {

    private final RedoService redos;

    public RedoController(RedoService redos) {
        this.redos = redos;
    }

    @GetMapping
    public List<RedoView> list() {
        return redos.list();
    }

    @PostMapping
    public RedoView create(@RequestBody CreateRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        return redos.create(req, me == null ? null : me.getUsername());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        redos.delete(id);
        return ResponseEntity.noContent().build();
    }
}
