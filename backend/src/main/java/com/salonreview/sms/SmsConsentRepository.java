package com.salonreview.sms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code marketing.contacts.sms_marketing_consent} — plain JdbcTemplate, same reason as
 * {@code MarketingContactsRepository}: this table is owned and migrated by the separate
 * salonLandings service, never mapped as a JPA entity here.
 */
@Repository
public class SmsConsentRepository {

    private final JdbcTemplate jdbcTemplate;

    public SmsConsentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@code false} for no matching contact and for a {@code NULL} consent value — fail closed,
     * matching how the existing Contacts tab already treats this nullable column. */
    public boolean hasMarketingConsent(String phoneNumber) {
        Boolean consent = jdbcTemplate.query(
                "SELECT sms_marketing_consent FROM marketing.contacts WHERE phone_number = ?",
                rs -> rs.next() ? (Boolean) rs.getObject("sms_marketing_consent") : null,
                phoneNumber);
        return Boolean.TRUE.equals(consent);
    }
}
