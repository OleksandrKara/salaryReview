package com.salonreview.repo;

import com.salonreview.domain.MarketingContactSquareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MarketingContactSquareLinkRepository extends JpaRepository<MarketingContactSquareLink, Long> {

    /** Last-10-digits match, not exact string equality — callers pass phone numbers sourced from
     * either this app's own (now E.164-normalized) tables or marketing.contacts' own,
     * differently-formatted column, and this table's own {@code phone_number} may itself predate
     * that normalization — see com.salonreview.util.PhoneNumbers' own doc comment. */
    @Query(value = "SELECT * FROM marketing_contact_square_link"
            + " WHERE RIGHT(regexp_replace(phone_number, '[^0-9]', '', 'g'), 10)"
            + " = RIGHT(regexp_replace(:phoneNumber, '[^0-9]', '', 'g'), 10) LIMIT 1",
            nativeQuery = true)
    Optional<MarketingContactSquareLink> findByPhoneNumber(@Param("phoneNumber") String phoneNumber);
}
