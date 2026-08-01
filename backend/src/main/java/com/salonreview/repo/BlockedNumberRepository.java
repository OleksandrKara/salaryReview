package com.salonreview.repo;

import com.salonreview.domain.BlockedNumber;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BlockedNumberRepository extends JpaRepository<BlockedNumber, String> {

    /** Batch existence check for the conversation list (see SmsActivityController#conversations)
     * — one query for every row on the page, not one per phone number. */
    List<BlockedNumber> findByPhoneNumberIn(Collection<String> phoneNumbers);
}
