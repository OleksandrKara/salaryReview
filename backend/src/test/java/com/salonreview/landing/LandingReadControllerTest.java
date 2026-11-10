package com.salonreview.landing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.sms.ConsultationOfferService;
import com.salonreview.sms.PromoConfigService;
import com.salonreview.sms.RebookingPromoSigner;
import com.salonreview.tracking.TrackingConfigService;

class LandingReadControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BusinessRepository businesses;
    private LandingSquareGateway square;
    private TrackingConfigService tracking;
    private RebookingPromoSigner signer;
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        businesses = mock(BusinessRepository.class);
        square = mock(LandingSquareGateway.class);
        tracking = mock(TrackingConfigService.class);
        signer = mock(RebookingPromoSigner.class);
        jdbc = mock(JdbcTemplate.class);
        LandingBusinessResolver resolver = new LandingBusinessResolver(businesses);
        LandingReadController controller = new LandingReadController(resolver, new NailsLandingService(square),
                new PmuLandingService(square, mock(ConsultationOfferService.class)),
                new ExperimentLandingService(jdbc), signer, mock(PromoConfigService.class), tracking);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new LandingExceptionAdvice()).build();

        Business pmu = mock(Business.class);
        when(pmu.getId()).thenReturn(2L);
        when(pmu.getName()).thenReturn("Anna Kara PMU");
        when(pmu.getTimezone()).thenReturn("America/Los_Angeles");
        when(businesses.findByPublicDomain("book.pmu-annakara.com")).thenReturn(Optional.of(pmu));
        when(businesses.findByPublicDomain("mani.akluxnails.com")).thenReturn(Optional.empty());

        when(square.items(anyLong())).thenReturn(nailsCatalog());
        when(square.team(anyLong())).thenReturn(team());
    }

    private static JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static JsonNode variation(String id, String name, long cents, int minutes, String... team) throws Exception {
        Map<String, Object> vd = new LinkedHashMap<>();
        vd.put("name", name);
        vd.put("price_money", Map.of("amount", cents, "currency", "USD"));
        vd.put("service_duration", minutes * 60_000L);
        vd.put("team_member_ids", List.of(team));
        vd.put("available_for_booking", true);
        return JSON.valueToTree(Map.of("id", id, "version", 1700000000001L, "item_variation_data", vd));
    }

    private static JsonNode item(String id, JsonNode... variations) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "item " + id);
        data.put("variations", List.of(variations));
        return JSON.valueToTree(Map.of("id", id, "item_data", data));
    }

    private static Map<String, JsonNode> nailsCatalog() throws Exception {
        Map<String, JsonNode> items = new LinkedHashMap<>();
        items.put("OPNE2Z2QTZR2USBD7AUJRRYF", item("OPNE2Z2QTZR2USBD7AUJRRYF",
                variation("M_TOP", "Top Nail Artist", 8500, 120, "T1"),
                variation("M_REG", "Nail Artist", 6500, 120, "T2")));
        items.put("OCMEURM3ONES53H4TCHRY5BS", item("OCMEURM3ONES53H4TCHRY5BS",
                variation("MR_TOP", "Top Nail Artist", 10000, 120, "T1"),
                variation("MR_REG", "Nail Artist", 8000, 120, "T2")));
        items.put("QSJQCUYORMRDKQH4BTDUERIP", item("QSJQCUYORMRDKQH4BTDUERIP",
                variation("P_TOP", "Top Nail Artist", 9000, 60, "T1")));
        items.put("MS7R7N3RWRJUT3U6MT2GL5W2", item("MS7R7N3RWRJUT3U6MT2GL5W2",
                variation("PR_TOP", "Top Nail Artist", 11000, 60, "T1")));
        items.put("7RJJPUA5OTN5LL33J2CL3G6M", item("7RJJPUA5OTN5LL33J2CL3G6M",
                variation("D", "Design", 2000, 30, "T1")));
        items.put("HOQVAY5LVBH65RJHISMHLQVF", item("HOQVAY5LVBH65RJHISMHLQVF",
                variation("NUEKRALUQ5TSZZGP346BCL4Y", "Request", 0, 120, "T1")));
        return items;
    }

    private static Map<String, JsonNode> team() throws Exception {
        Map<String, JsonNode> team = new LinkedHashMap<>();
        team.put("T1", node("{\"id\":\"T1\",\"given_name\":\"Lucy\",\"family_name\":\"Smith\"}"));
        team.put("T2", node("{\"id\":\"T2\",\"given_name\":\"Ana\"}"));
        return team;
    }

    @Test
    void healthIsOk() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void servicesHaveShapeAndCacheControl() throws Exception {
        mvc.perform(get("/api/services").header("Host", "mani.akluxnails.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=3600, stale-while-revalidate=86400"))
                .andExpect(jsonPath("$.manicure.slug").value("manicure"))
                .andExpect(jsonPath("$.manicure.is_first_time_offer").value(true))
                .andExpect(jsonPath("$.manicure.pricing[0].tier").value("top"))
                .andExpect(jsonPath("$.manicure.pricing[0].price").value(85.0))
                .andExpect(jsonPath("$.manicure.pricing[0].compare_at_price").value(100.0))
                .andExpect(jsonPath("$.manicure.pricing[0].duration_minutes").value(120))
                .andExpect(jsonPath("$.manicure.pricing[1].tier").value("regular"))
                .andExpect(jsonPath("$.design_addon.price").value(20.0))
                .andExpect(jsonPath("$.design_addon.requires_slug").value("manicure"))
                .andExpect(jsonPath("$.four_hand_request.price").value(249.0))
                .andExpect(jsonPath("$.four_hand_request.compare_at_price").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"compare_at_price\":null")));
    }

    @Test
    void artistsAreSortedTopFirstWithNullBio() throws Exception {
        mvc.perform(get("/api/artists").header("Host", "mani.akluxnails.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("T1"))
                .andExpect(jsonPath("$[0].display_name").value("Lucy S."))
                .andExpect(jsonPath("$[0].tier").value("top"))
                .andExpect(jsonPath("$[0].bio").doesNotExist())
                .andExpect(jsonPath("$[1].display_name").value("Ana"))
                .andExpect(jsonPath("$[1].tier").value("regular"));
    }

    @Test
    void hostResolvesBusinessAndFallsBackToLegacy() throws Exception {
        mvc.perform(get("/api/services").header("Host", "Book.PMU-Annakara.com:443")).andExpect(status().isOk());
        verify(square).items(2L);
        mvc.perform(get("/api/services").header("Host", "unknown.example.com")).andExpect(status().isOk());
        verify(square).items(1L);
    }

    @Test
    void availabilityPicksCheapestSlotAndFlagsBestPrice() throws Exception {
        when(square.availability(anyLong(), anyList(), anyList(), any(), any())).thenAnswer(inv -> {
            List<String> variations = inv.getArgument(1);
            String start = "2026-12-01T18:00:00Z";
            return List.of(node("{\"start_at\":\"" + start + "\",\"appointment_segments\":[{\"team_member_id\":\""
                    + (variations.get(0).equals("M_TOP") ? "T1" : "T2") + "\"}]}"));
        });
        mvc.perform(get("/api/availability?services=manicure&artist=any&days=7").header("Host", "mani.akluxnails.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0]").value("manicure"))
                .andExpect(jsonPath("$.artist_selection").value("any"))
                .andExpect(jsonPath("$.slots.length()").value(1))
                .andExpect(jsonPath("$.slots[0].tier").value("regular"))
                .andExpect(jsonPath("$.slots[0].price").value(65.0))
                .andExpect(jsonPath("$.slots[0].advertised_price").value(85.0))
                .andExpect(jsonPath("$.slots[0].savings").value(20.0))
                .andExpect(jsonPath("$.slots[0].is_best_price").value(true))
                .andExpect(jsonPath("$.slots[0].end_at").value("2026-12-01T20:00:00Z"))
                .andExpect(jsonPath("$.slots[0].artist_name").value("Ana"))
                .andExpect(jsonPath("$.slots[0].segments[0].service_slug").value("manicure"));
    }

    @Test
    void availabilityRejectsUnknownSlugAndBadCart() throws Exception {
        mvc.perform(get("/api/availability?services=nope&artist=any&days=7").header("Host", "mani.akluxnails.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown service(s): nope"));
        mvc.perform(get("/api/availability?services=design").header("Host", "mani.akluxnails.com"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/availability?services=manicure&days=40").header("Host", "mani.akluxnails.com"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void fourHandAvailabilityIsFlatZeroPrice() throws Exception {
        when(square.availability(anyLong(), eq(List.of("NUEKRALUQ5TSZZGP346BCL4Y")), anyList(), any(), any()))
                .thenReturn(List.of(node("{\"start_at\":\"2026-12-01T18:00:00Z\",\"appointment_segments\":[{}]}")));
        mvc.perform(get("/api/availability?services=four-hand-request&days=7").header("Host", "mani.akluxnails.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].price").value(0.0))
                .andExpect(jsonPath("$.slots[0].tier").value("regular"))
                .andExpect(jsonPath("$.slots[0].is_best_price").value(true))
                .andExpect(jsonPath("$.slots[0].end_at").value("2026-12-01T20:00:00Z"));
    }

    @Test
    void experimentFallsBackToNeutralBodyWhenSchemaMissing() throws Exception {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), eq(String.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("no schema"));
        mvc.perform(get("/api/experiments/mani"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"landing_page_id\":null,\"experiment_status\":\"none\",\"variants\":[]}"));
    }

    @Test
    void experimentVariantContentIsRawJson() throws Exception {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT id::text FROM marketing.landing_pages"),
                eq(String.class), any(Object[].class))).thenReturn(List.of("page-1"));
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("key = ?"), any(Object[].class)))
                .thenReturn(List.of(new LinkedHashMap<>(Map.of("id", "v1", "name", "Version 7", "weight", 50,
                        "content", "{\"headline\":\"Hi\",\"n\":3,\"tags\":[\"a\"],\"nested\":{\"x\":null}}"))));
        mvc.perform(get("/api/experiments/mani").param("variant_key", "version-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiment_status").value("forced"))
                .andExpect(jsonPath("$.variants[0].content.headline").value("Hi"))
                .andExpect(jsonPath("$.variants[0].content.n").value(3))
                .andExpect(jsonPath("$.variants[0].content.tags[0]").value("a"))
                .andExpect(jsonPath("$.variants[0].content.nested.x").doesNotExist())
                .andExpect(jsonPath("$.variants[0].content.array").doesNotExist())
                .andExpect(jsonPath("$.variants[0].content.bigDecimal").doesNotExist());
    }

    @Test
    void invalidPromoReturnsNulls() throws Exception {
        when(signer.verify("x", 1L, "x")).thenReturn(false);
        mvc.perform(get("/api/promos/verify?code=x&exp=1&sig=x").header("Host", "mani.akluxnails.com"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"valid\":false,\"discount_amount\":null,\"min_spend\":null}"));
    }

    @Test
    void clarityConfigUsesHostWithoutPort() throws Exception {
        when(tracking.clarityProjectIdFor("book.pmu-annakara.com")).thenReturn("abc123");
        mvc.perform(get("/api/tracking/clarity-config").header("Host", "Book.pmu-annakara.com:8443"))
                .andExpect(jsonPath("$.clarity_project_id").value("abc123"));
        mvc.perform(get("/api/tracking/clarity-config").header("Host", "mani.akluxnails.com"))
                .andExpect(content().string("{\"clarity_project_id\":null}"));
    }

    @Test
    void calendarEventEscapesTextAndUsesCrlf() throws Exception {
        mvc.perform(get("/api/calendar/event.ics").param("title", "Mani, Pedi; Gel")
                        .param("start", "2026-12-01T18:00:00Z").param("duration_minutes", "90"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/calendar; charset=utf-8"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"appointment.ics\""))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("SUMMARY:Mani\\, Pedi\\; Gel\r\n"),
                        org.hamcrest.Matchers.containsString("DTSTART:20261201T180000Z\r\n"),
                        org.hamcrest.Matchers.containsString("DTEND:20261201T193000Z\r\n"),
                        org.hamcrest.Matchers.containsString("UID:20261201T180000Z@akluxnails\r\n"))));
    }
}
