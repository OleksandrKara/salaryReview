package com.salonreview.seo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Shared factory for the Google-API {@code RestClient}s in this package. This app's default Spring
 * message converters are wired for a newer Jackson major version ({@code tools.jackson}) — a real
 * runtime mismatch was hit wiring this feature up (deserializing into a
 * {@code com.fasterxml.jackson.databind.JsonNode} via the default converter threw
 * {@code InvalidDefinitionException}, not a compile error, since both Jackson generations' classes
 * are present on the classpath). {@link com.salonreview.square.SquareClient} avoids this the same
 * way: explicitly registering its own {@code MappingJackson2HttpMessageConverter} bound to a
 * {@code com.fasterxml.jackson.databind.ObjectMapper} instead of relying on the app's default
 * converters — this factory does the same, once, for every client in this package rather than
 * repeating the wiring four times.
 */
final class GoogleRestClients {

    private GoogleRestClients() {
    }

    static RestClient.Builder builder(String baseUrl) {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new MappingJackson2HttpMessageConverter(mapper));
                    converters.add(new StringHttpMessageConverter());
                });
    }
}
