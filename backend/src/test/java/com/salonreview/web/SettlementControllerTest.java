package com.salonreview.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test against the running Postgres from docker-compose (port 5432).
 * Asserts against Anna's V2 seeded entry — that row is guaranteed to exist on a fresh DB.
 *
 * NOTE: Testcontainers would be the better isolation strategy here, but the docker-java
 * client bundled in TC 1.21.x and Docker Desktop 29.x weren't talking on this machine.
 * Falling back to dev DB to keep momentum; can revisit later (TODO).
 *
 * Prereqs: `docker compose up -d postgres` and migrations applied.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void getSettlements_returnsAnnaWithExpectedNumbersAndMessage() throws Exception {
        // Anna is always first alphabetically among active providers; index 0.
        mvc.perform(get("/api/pay-periods/1/settlements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)))

                .andExpect(jsonPath("$[0].providerName").value("Anna"))
                .andExpect(jsonPath("$[0].procedures").value(5))
                .andExpect(jsonPath("$[0].zelleToProvider").value(284.55))
                .andExpect(jsonPath("$[0].cashToSalon").value(160.05))
                .andExpect(jsonPath("$[0].tipsAfterFee").value(71.70))
                .andExpect(jsonPath("$[0].messageText", containsString("#salary 1-15 May 2026")))
                .andExpect(jsonPath("$[0].messageText", containsString("Zelle AK to Anna: $284.55")))
                .andExpect(jsonPath("$[0].messageText", containsString("Cash from Anna to AK: $160.05")));
    }

    @Test
    void getSettlements_returns404ForUnknownPeriod() throws Exception {
        mvc.perform(get("/api/pay-periods/9999/settlements"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }
}
