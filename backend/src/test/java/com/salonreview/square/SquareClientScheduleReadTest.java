package com.salonreview.square;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.salonreview.sms.ProviderScheduleTestFixtures.BASE;
import static org.assertj.core.api.Assertions.*;

class SquareClientScheduleReadTest {
    private HttpServer server;
    private SquareClient client;
    private final List<Request> requests = new ArrayList<>();
    private Function<Request, String> response;
    private record Request(String method, String path, String query, String body) {}

    @AfterEach
    void closeServer() { if (server != null) server.stop(0); }

    private void start(Function<Request, String> respond) throws IOException {
        response = respond;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        var http = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new MappingJackson2HttpMessageConverter(new ObjectMapper()));
                }).build();
        client = new SquareClient(http, "LOC");
    }

    private void handle(HttpExchange exchange) throws IOException {
        var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requests.add(request);
        String body = response.apply(request);
        if (body == null) exchange.sendResponseHeaders(204, -1);
        else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Test
    void requiresSellerWideScopeAndCachesOnlyIntrospection() throws Exception {
        start(request -> "{\"scopes\":[\"APPOINTMENTS_READ\",\"APPOINTMENTS_ALL_READ\"]}");
        assertThat(client.hasCompleteScheduleBookingAccess()).isTrue();
        assertThat(client.hasCompleteScheduleBookingAccess()).isTrue();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().method()).isEqualTo("POST");
        assertThat(requests.getFirst().path()).isEqualTo("/oauth2/token/status");
        assertThat(requests.getFirst().body()).isEqualTo("{}");
    }

    @Test
    void buyerScopeOrSquareErrorCannotPretendCalendarIsComplete() throws Exception {
        start(request -> "{\"scopes\":[\"APPOINTMENTS_READ\"]}");
        assertThat(client.hasCompleteScheduleBookingAccess()).isFalse();
        response = request -> "{\"errors\":[{\"code\":\"FORBIDDEN\"}]}";
        assertThatThrownBy(client::scheduleBusinessProfile).isInstanceOf(IllegalStateException.class);
        response = request -> "{}";
        assertThatThrownBy(client::scheduleBusinessProfile).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void availabilitySearchUsesRealServiceProviderAndLocationAndDistinguishesValidEmptyFromFailure() throws Exception {
        start(request -> "{}");
        assertThat(client.scheduleAvailability("TEAM", "SHORT", BASE, BASE.plus(Duration.ofHours(24)))).isEmpty();
        var body = new ObjectMapper().readTree(requests.getFirst().body()).path("query").path("filter");
        assertThat(body.path("location_id").asText()).isEqualTo("LOC");
        assertThat(body.path("segment_filters").get(0).path("service_variation_id").asText()).isEqualTo("SHORT");
        assertThat(body.path("segment_filters").get(0).path("team_member_id_filter").path("any").get(0).asText()).isEqualTo("TEAM");
        response = request -> "{\"errors\":[{\"code\":\"BAD_REQUEST\"}]}";
        assertThatThrownBy(() -> client.scheduleAvailability("TEAM", "SHORT", BASE, BASE.plusSeconds(86400)))
                .isInstanceOf(IllegalStateException.class);
        response = request -> null;
        assertThatThrownBy(() -> client.scheduleAvailability("TEAM", "SHORT", BASE, BASE.plusSeconds(86400)))
                .isInstanceOf(IllegalStateException.class);
        int calls = requests.size();
        assertThatThrownBy(() -> client.scheduleAvailability("TEAM", "SHORT", BASE, BASE.plusSeconds(86399)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasSize(calls);
    }

    @Test
    void bookableTeamMembersFollowAllPagesAndFailOnRepeatedCursor() throws Exception {
        start(request -> request.query().contains("cursor=next")
                ? "{\"team_member_booking_profiles\":[{\"team_member_id\":\"B\",\"is_bookable\":true}]}"
                : "{\"team_member_booking_profiles\":[{\"team_member_id\":\"A\",\"is_bookable\":true},{\"team_member_id\":\"X\",\"is_bookable\":false}],\"cursor\":\"next\"}");
        assertThat(client.scheduleBookableTeamMembers()).extracting(SquareScheduleData.TeamProfile::teamMemberId).containsExactly("A", "B");
        assertThat(requests).allSatisfy(request -> assertThat(request.query()).contains("location_id=LOC", "bookable_only=true"));
        response = request -> "{\"cursor\":\"same\"}";
        assertThatThrownBy(client::scheduleBookableTeamMembers).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void freshBookingReadFollowsPagesChunksAndNeverUsesPayrollCache() throws Exception {
        start(request -> request.query().contains("cursor=next")
                ? "{\"bookings\":[{\"id\":\"B\",\"location_id\":\"LOC\",\"status\":\"ACCEPTED\"}]}"
                : "{\"bookings\":[{\"id\":\"A\",\"location_id\":\"LOC\",\"status\":\"PENDING\"}],\"cursor\":\"next\"}");
        var end = BASE.plus(Duration.ofDays(31));
        assertThat(client.scheduleBookings(BASE, end)).extracting(SquareScheduleData.Booking::id).containsExactly("A", "B");
        assertThat(requests).hasSize(4);
        client.scheduleBookings(BASE, end);
        assertThat(requests).hasSize(8);
        assertThat(requests).allSatisfy(request -> assertThat(request.query()).contains("location_id=LOC", "start_at_min=", "start_at_max="));
    }

    @Test
    void errorOnLaterBookingPageAndWrongLocationAreRejectedRatherThanPartiallyReturned() throws Exception {
        start(request -> request.query().contains("cursor=next") ? "{\"errors\":[{\"code\":\"FORBIDDEN\"}]}"
                : "{\"bookings\":[{\"id\":\"A\",\"location_id\":\"LOC\"}],\"cursor\":\"next\"}");
        assertThatThrownBy(() -> client.scheduleBookings(BASE, BASE.plusSeconds(86400))).isInstanceOf(IllegalStateException.class);
        response = request -> "{\"bookings\":[{\"id\":\"A\",\"location_id\":\"OTHER\"}]}";
        assertThatThrownBy(() -> client.scheduleBookings(BASE, BASE.plusSeconds(86400))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void catalogUsesOnlyBookablePositiveDurationAssignedAndLocationEligibleVariations() throws Exception {
        start(request -> """
                {"objects":[{"id":"ITEM","item_data":{"name":"Service","variations":[
                    {"id":"SHORT","version":7,"item_variation_data":{"service_duration":1800000,"available_for_booking":true,"team_member_ids":["TEAM"]}},
                    {"id":"ZERO","version":1,"item_variation_data":{"service_duration":0,"available_for_booking":true,"team_member_ids":["TEAM"]}},
                    {"id":"DISABLED","version":1,"item_variation_data":{"service_duration":1800000,"available_for_booking":false,"team_member_ids":["TEAM"]}},
                    {"id":"NO_TEAM","version":1,"item_variation_data":{"service_duration":1800000,"available_for_booking":true}},
                    {"id":"OTHER_LOC","version":1,"present_at_all_locations":false,"present_at_location_ids":["OTHER"],"item_variation_data":{"service_duration":1800000,"available_for_booking":true,"team_member_ids":["TEAM"]}},
                    {"id":"ABSENT","version":1,"absent_at_location_ids":["LOC"],"item_variation_data":{"service_duration":1800000,"available_for_booking":true,"team_member_ids":["TEAM"]}}
                ]}}]}
                """);
        assertThat(client.scheduleBookableServices()).containsExactly(new SquareScheduleData.Service("SHORT", 7, "Service", 30, List.of("TEAM")));
        assertThat(requests.getFirst().query()).contains("types=ITEM");
    }
}
