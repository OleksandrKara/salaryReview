package com.salonreview.landing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pydantic-style field validation over a JSON object: collects errors in the
 * {@code {"detail": [{type, loc, msg}]}} shape FastAPI returned for a 422.
 */
final class LandingBody {

    static final class Invalid extends RuntimeException {
        private final transient List<Map<String, Object>> detail;

        Invalid(List<Map<String, Object>> detail) {
            super("Request validation failed");
            this.detail = detail;
        }

        List<Map<String, Object>> detail() {
            return detail;
        }
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private final JsonNode node;
    private final List<Object> path;
    private final List<Map<String, Object>> errors;

    private LandingBody(JsonNode node, List<Object> path, List<Map<String, Object>> errors) {
        this.node = node;
        this.path = path;
        this.errors = errors;
    }

    static LandingBody root(JsonNode node) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Object> path = new ArrayList<>();
        path.add("body");
        if (node == null || !node.isObject()) {
            LandingBody b = new LandingBody(null, path, errors);
            b.error(path, "model_attributes_type", "Input should be a valid dictionary or object to extract fields from");
            throw new Invalid(errors);
        }
        return new LandingBody(node, path, errors);
    }

    void finish() {
        if (!errors.isEmpty()) throw new Invalid(errors);
    }

    private void error(List<Object> loc, String type, String msg) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("loc", loc);
        e.put("msg", msg);
        errors.add(e);
    }

    private List<Object> loc(String field) {
        List<Object> l = new ArrayList<>(path);
        l.add(field);
        return l;
    }

    private JsonNode raw(String field) {
        if (node == null) return null;
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n;
    }

    String string(String field, boolean required, int min, int max) {
        JsonNode n = raw(field);
        if (n == null) {
            if (required) error(loc(field), "missing", "Field required");
            return null;
        }
        if (!n.isTextual()) {
            error(loc(field), "string_type", "Input should be a valid string");
            return null;
        }
        String s = n.asText();
        int len = s.codePointCount(0, s.length());
        if (len < min) {
            error(loc(field), "string_too_short", "String should have at least " + min + " character" + (min == 1 ? "" : "s"));
            return null;
        }
        if (max > 0 && len > max) {
            error(loc(field), "string_too_long", "String should have at most " + max + " characters");
            return null;
        }
        return s;
    }

    String string(String field, int max) {
        return string(field, false, 0, max);
    }

    String email(String field) {
        JsonNode n = raw(field);
        if (n == null) return null;
        if (!n.isTextual()) {
            error(loc(field), "string_type", "Input should be a valid string");
            return null;
        }
        String s = n.asText();
        if (!EMAIL.matcher(s).matches()) {
            error(loc(field), "value_error", "value is not a valid email address");
            return null;
        }
        int at = s.lastIndexOf('@');
        return s.substring(0, at + 1) + s.substring(at + 1).toLowerCase(java.util.Locale.ROOT);
    }

    boolean bool(String field, boolean dflt) {
        JsonNode n = raw(field);
        if (n == null) return dflt;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isTextual()) {
            String t = n.asText().toLowerCase(java.util.Locale.ROOT);
            if (List.of("true", "1", "t", "y", "yes", "on").contains(t)) return true;
            if (List.of("false", "0", "f", "n", "no", "off").contains(t)) return false;
        } else if (n.isIntegralNumber() && (n.asInt() == 0 || n.asInt() == 1)) {
            return n.asInt() == 1;
        }
        error(loc(field), "bool_parsing", "Input should be a valid boolean, unable to interpret input");
        return dflt;
    }

    Integer integer(String field, boolean required, Integer min) {
        JsonNode n = raw(field);
        if (n == null) {
            if (required) error(loc(field), "missing", "Field required");
            return null;
        }
        Integer value = null;
        if (n.isIntegralNumber() && n.canConvertToInt()) {
            value = n.asInt();
        } else if (n.isFloatingPointNumber() && n.asDouble() == Math.rint(n.asDouble()) && Math.abs(n.asDouble()) < Integer.MAX_VALUE) {
            value = (int) n.asDouble();
        } else if (n.isTextual() && n.asText().trim().matches("[+-]?\\d{1,9}")) {
            value = Integer.parseInt(n.asText().trim());
        }
        if (value == null) {
            error(loc(field), "int_parsing", "Input should be a valid integer, unable to parse string as an integer");
            return null;
        }
        if (min != null && value < min) {
            error(loc(field), "greater_than_equal", "Input should be greater than or equal to " + min);
            return null;
        }
        return value;
    }

    Long longInteger(String field, boolean required) {
        JsonNode n = raw(field);
        if (n == null) {
            if (required) error(loc(field), "missing", "Field required");
            return null;
        }
        Long value = null;
        if (n.isIntegralNumber() && n.canConvertToLong()) {
            value = n.asLong();
        } else if (n.isTextual() && n.asText().trim().matches("[+-]?\\d{1,18}")) {
            value = Long.parseLong(n.asText().trim());
        }
        if (value == null) {
            error(loc(field), "int_parsing", "Input should be a valid integer, unable to parse string as an integer");
        }
        return value;
    }

    Map<String, Object> object(String field) {
        JsonNode n = raw(field);
        if (n == null) return new LinkedHashMap<>();
        if (!n.isObject()) {
            error(loc(field), "dict_type", "Input should be a valid dictionary");
            return new LinkedHashMap<>();
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(n,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { });
    }

    /** Nested object, or null when absent; required nested objects report "missing". */
    LandingBody child(String field, boolean required) {
        JsonNode n = raw(field);
        if (n == null) {
            if (required) error(loc(field), "missing", "Field required");
            return null;
        }
        if (!n.isObject()) {
            error(loc(field), "model_attributes_type", "Input should be a valid dictionary or object to extract fields from");
            return null;
        }
        return new LandingBody(n, loc(field), errors);
    }

    List<LandingBody> children(String field, boolean required) {
        JsonNode n = raw(field);
        if (n == null) {
            if (required) error(loc(field), "missing", "Field required");
            return null;
        }
        if (!n.isArray()) {
            error(loc(field), "list_type", "Input should be a valid list");
            return null;
        }
        List<LandingBody> out = new ArrayList<>();
        int i = 0;
        for (JsonNode item : n) {
            List<Object> p = loc(field);
            p.add(i++);
            if (!item.isObject()) {
                error(p, "model_attributes_type", "Input should be a valid dictionary or object to extract fields from");
            } else {
                out.add(new LandingBody(item, p, errors));
            }
        }
        return out;
    }

    /** Optional nested TrackingSnapshot; its visitor_id is required when the snapshot is present. */
    LandingTracking tracking(String field) {
        LandingBody t = child(field, false);
        return t == null ? null : t.snapshot();
    }

    /** This object read as a TrackingSnapshot. */
    LandingTracking snapshot() {
        return new LandingTracking(string("visitor_id", true, 0, 64), string("landing_path", 500),
                string("referrer", 1000), string("utm_source", 200), string("utm_medium", 200),
                string("utm_campaign", 200), string("utm_term", 200), string("utm_content", 200),
                string("fbclid", 500), string("gclid", 500), string("landing_page_id", 64),
                string("variant_id", 64));
    }

    /** Snapshot as the whole request body. */
    LandingTracking snapshotFinished() {
        LandingTracking t = snapshot();
        finish();
        return t;
    }

    void rejectLiteral(String field, String value, String expected) {
        error(loc(field), "literal_error", "Input should be " + expected);
    }
}
