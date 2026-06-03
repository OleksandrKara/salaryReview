package com.salonreview.web;

import com.salonreview.square.SquareClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand "Sync now": drops the cached Square reads so the next page load pulls fresh from Square.
 * Any signed-in user can trigger it (it only busts a read cache); the settlement views then re-fetch and
 * the "synced" timestamp updates. Lets anyone clear up a suspected gap between the portal and Square.
 */
@RestController
@RequestMapping("/api/sync")
public class SquareSyncController {

    private final SquareClient square;

    public SquareSyncController(SquareClient square) {
        this.square = square;
    }

    @PostMapping
    public ResponseEntity<Void> sync() {
        square.invalidate();
        return ResponseEntity.noContent().build();
    }
}
