package com.salonreview.repo;

import com.salonreview.domain.MailchimpDeferredSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MailchimpDeferredSendRepository extends JpaRepository<MailchimpDeferredSend, Long> {

    List<MailchimpDeferredSend> findByStateAndNextAttemptAtBefore(String state, Instant now);
}
