package com.salonreview.sms;

import com.salonreview.util.PhoneNumbers;
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
     * matching how the existing Contacts tab already treats this nullable column. Last-10-digits
     * match, not exact string equality — marketing.contacts' own phone-number format isn't
     * guaranteed to match whatever format the caller is holding (e.g. this app's own E.164-
     * normalized numbers) — see com.salonreview.util.PhoneNumbers' own doc comment. */
    public boolean hasMarketingConsent(String phoneNumber) {
        Boolean consent = jdbcTemplate.query(
                "SELECT sms_marketing_consent FROM marketing.contacts"
                        + " WHERE RIGHT(regexp_replace(phone_number, '[^0-9]', '', 'g'), 10) = ?",
                rs -> rs.next() ? (Boolean) rs.getObject("sms_marketing_consent") : null,
                PhoneNumbers.last10Digits(phoneNumber));
        return Boolean.TRUE.equals(consent);
    }
}
