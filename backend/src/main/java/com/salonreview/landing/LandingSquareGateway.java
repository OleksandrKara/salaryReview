package com.salonreview.landing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.square.SquareConnectionService;

/** The only place the landing read endpoints touch Square; tests replace it with a mock. */
@Component
public class LandingSquareGateway {

    public record Credentials(String applicationId, String locationId) {
    }

    private final SquareClientProvider clients;
    private final SquareConnectionService connections;

    public LandingSquareGateway(SquareClientProvider clients, SquareConnectionService connections) {
        this.clients = clients;
        this.connections = connections;
    }

    public Map<String, JsonNode> items(long businessId) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode o : clients.forBusiness(businessId).landingCatalogObjects("ITEM")) {
            if (o.path("is_deleted").asBoolean(false)) continue;
            byId.put(o.path("id").asText(), o);
        }
        return byId;
    }

    public Map<String, String> categoryNames(long businessId) {
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonNode o : clients.forBusiness(businessId).landingCatalogObjects("CATEGORY")) {
            names.put(o.path("id").asText(), o.path("category_data").path("name").asText(""));
        }
        return names;
    }

    public Map<String, JsonNode> team(long businessId) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode m : clients.forBusiness(businessId).landingTeamMembers()) {
            byId.put(m.path("id").asText(), m);
        }
        return byId;
    }

    public List<JsonNode> availability(long businessId, List<String> variationIds, List<String> teamMemberIds,
                                       String startAt, String endAt) {
        SquareClient client = clients.forBusiness(businessId);
        return client.landingSearchAvailability(variationIds, teamMemberIds, startAt, endAt);
    }

    public Credentials credentials(long businessId) {
        SquareConnectionService.PlainCredentials c = connections.plainCredentials(businessId)
                .orElseThrow(() -> new LandingException(502, "Square is not connected for this business"));
        return new Credentials(c.applicationId(), c.locationId());
    }

    public JsonNode location(long businessId) {
        return clients.forBusiness(businessId).landingLocation();
    }

    public JsonNode createBooking(long businessId, String customerId, String startAt, String teamMemberId,
                                  String variationId, long variationVersion, int durationMinutes, String customerNote,
                                  String sellerNote) {
        return clients.forBusiness(businessId).landingCreateBooking(customerId, startAt, teamMemberId, variationId,
                variationVersion, durationMinutes, customerNote, sellerNote);
    }

    public void cancelBooking(long businessId, String bookingId) {
        clients.forBusiness(businessId).landingCancelBooking(bookingId);
    }

    public JsonNode createPayment(long businessId, String idempotencyKey, String sourceId, long amountCents,
                                  String customerId, String note) {
        return clients.forBusiness(businessId).landingCreatePayment(idempotencyKey, sourceId, amountCents, customerId, note);
    }

    public JsonNode refundPayment(long businessId, String paymentId, long amountCents) {
        return clients.forBusiness(businessId).landingRefundPayment(paymentId, amountCents);
    }
}
