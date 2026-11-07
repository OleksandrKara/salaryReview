package com.salonreview.square;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salonreview.config.BusinessSetupIncompleteException;
import com.salonreview.config.SquareCredentialCipher;
import com.salonreview.config.SquareProperties;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The only Square client allowed to write, and only to Labor timecards and break types. Every call
 * goes through {@link #isAllowed}, which is covered by a unit test; anything else throws.
 */
@Component
public class SquareLaborClient {

    private static final Pattern TIMECARD_PATH = Pattern.compile("^/v2/labor/timecards(/search|/[A-Za-z0-9_-]+)?$");
    private static final Pattern BREAK_TYPE_PATH = Pattern.compile("^/v2/labor/break-types(/[A-Za-z0-9_-]+)?$");

    private final SquareConnectionRepository connections;
    private final SquareCredentialCipher cipher;
    private final ObjectMapper mapper = new ObjectMapper();

    public SquareLaborClient(SquareConnectionRepository connections, SquareCredentialCipher cipher) {
        this.connections = connections;
        this.cipher = cipher;
    }

    public static boolean isAllowed(HttpMethod method, String path) {
        if (path == null || path.contains("..") || path.contains("?")) return false;
        if (TIMECARD_PATH.matcher(path).matches()) {
            boolean search = path.endsWith("/search");
            boolean base = path.equals("/v2/labor/timecards");
            if (search) return method == HttpMethod.POST;
            if (base) return method == HttpMethod.POST;
            return method == HttpMethod.GET || method == HttpMethod.PUT;
        }
        if (BREAK_TYPE_PATH.matcher(path).matches()) {
            boolean base = path.equals("/v2/labor/break-types");
            if (base) return method == HttpMethod.GET || method == HttpMethod.POST;
            return method == HttpMethod.GET;
        }
        return false;
    }

    /** Timecards whose start falls in [from, to), all pages. */
    public List<JsonNode> searchTimecards(Long businessId, String locationId, Instant from, Instant to) {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode body = mapper.createObjectNode();
            ObjectNode filter = body.putObject("query").putObject("filter");
            filter.putArray("location_ids").add(locationId);
            ObjectNode start = filter.putObject("start");
            start.put("start_at", from.toString());
            start.put("end_at", to.toString());
            body.put("limit", 200);
            if (cursor != null) body.put("cursor", cursor);
            JsonNode resp = call(businessId, HttpMethod.POST, "/v2/labor/timecards/search", body);
            JsonNode cards = resp.path("timecards");
            if (cards.isArray()) cards.forEach(out::add);
            cursor = resp.hasNonNull("cursor") ? resp.get("cursor").asText() : null;
        } while (cursor != null);
        return out;
    }

    public JsonNode createTimecard(Long businessId, JsonNode timecard) {
        ObjectNode body = mapper.createObjectNode();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.set("timecard", timecard);
        return call(businessId, HttpMethod.POST, "/v2/labor/timecards", body).path("timecard");
    }

    public JsonNode updateTimecard(Long businessId, String timecardId, JsonNode timecard) {
        ObjectNode body = mapper.createObjectNode();
        body.set("timecard", timecard);
        return call(businessId, HttpMethod.PUT, "/v2/labor/timecards/" + timecardId, body).path("timecard");
    }

    public List<JsonNode> listBreakTypes(Long businessId, String locationId) {
        JsonNode resp = call(businessId, HttpMethod.GET, "/v2/labor/break-types", null, "location_id=" + locationId);
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = resp.path("break_types");
        if (arr.isArray()) arr.forEach(out::add);
        return out;
    }

    public JsonNode createBreakType(Long businessId, String locationId, String name, int minutes, boolean paid) {
        ObjectNode body = mapper.createObjectNode();
        body.put("idempotency_key", UUID.randomUUID().toString());
        ObjectNode bt = body.putObject("break_type");
        bt.put("location_id", locationId);
        bt.put("break_name", name);
        bt.put("expected_duration", "PT" + minutes + "M");
        bt.put("is_paid", paid);
        return call(businessId, HttpMethod.POST, "/v2/labor/break-types", body).path("break_type");
    }

    private JsonNode call(Long businessId, HttpMethod method, String path, JsonNode body) {
        return call(businessId, method, path, body, null);
    }

    private JsonNode call(Long businessId, HttpMethod method, String path, JsonNode body, String query) {
        if (!isAllowed(method, path)) {
            throw new IllegalStateException("Square Labor client refuses " + method + " " + path);
        }
        SquareConnection connection = connections.findByBusinessId(businessId)
                .orElseThrow(() -> new BusinessSetupIncompleteException("square_not_connected",
                        "Connect Square before syncing timecards."));
        SquareProperties props = new SquareProperties();
        props.setEnvironment(connection.getEnvironment());
        props.setAccessToken(cipher.decrypt(connection.getAccessTokenEncrypted()));
        props.setLocationId(connection.getLocationId());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.apiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getAccessToken())
                .defaultHeader("Content-Type", "application/json");
        if (props.getApiVersion() != null && !props.getApiVersion().isBlank()) {
            builder.defaultHeader("Square-Version", props.getApiVersion());
        }
        RestClient http = builder.build();
        String uri = query == null ? path : path + "?" + query;
        RestClient.RequestBodySpec spec = http.method(method).uri(uri);
        String raw = (body == null ? spec : spec.body(body.toString())).retrieve().body(String.class);
        try {
            return raw == null || raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable Square Labor response", e);
        }
    }
}
