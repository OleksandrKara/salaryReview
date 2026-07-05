package com.salonreview.marketing;

import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class MarketingContactsService {

    private static final Logger log = LoggerFactory.getLogger(MarketingContactsService.class);

    // Square has no public API for a direct "view this booking" link (confirmed — nothing
    // documented), but the customer profile URL is a stable, long-standing Dashboard pattern.
    // The booking's own date/time/provider/services/price are shown inline in this table
    // anyway, so clicking through is only needed to see the customer's full Square history.
    private static final String SQUARE_CUSTOMER_PROFILE_URL = "https://app.squareup.com/dashboard/customers/%s";

    private final MarketingContactsRepository repository;

    public MarketingContactsService(MarketingContactsRepository repository) {
        this.repository = repository;
    }

    /** Never throws: same "this app's health must never depend on the other service's
     * schema" guarantee as MarketingDashboardService.dashboard.
     */
    public MarketingContactDto contacts() {
        try {
            List<Contact> contacts = repository.listAll().stream()
                    .map(MarketingContactsService::toContact)
                    .collect(Collectors.toList());
            return new MarketingContactDto(true, contacts);
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building contacts list", ex);
            return MarketingContactDto.unavailable();
        }
    }

    private static Contact toContact(MarketingContactsRepository.RawContact raw) {
        String squareProfileUrl = raw.squareCustomerId() == null
                ? null
                : String.format(SQUARE_CUSTOMER_PROFILE_URL, raw.squareCustomerId());
        return new Contact(
                raw.id().toString(),
                raw.givenName(),
                raw.phoneNumber(),
                raw.emailAddress(),
                raw.originalTrafficSource(),
                raw.marketingTrafficSource(),
                raw.smsMarketingConsent(),
                raw.emailMarketingConsent(),
                raw.squareBookingId() != null,
                squareProfileUrl,
                raw.bookingStatus(),
                raw.bookingStartAt(),
                raw.bookingServiceName(),
                raw.bookingPrice(),
                raw.bookingArtistName(),
                raw.createdAt()
        );
    }
}
