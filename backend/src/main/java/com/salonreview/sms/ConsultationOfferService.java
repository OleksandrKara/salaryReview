package com.salonreview.sms;

import com.salonreview.domain.ConsultationFollowUp;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.repo.ConsultationFollowUpRepository;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.square.SquareClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * What the pmu-annakara.com booking popup needs to show and keep the $75 OFF a client was sent
 * (owner request 2026-10-07: the discount has to be visible while booking, and only for clients who
 * really have it). {@link #check} answers whether a personal link's offer is live; {@link #claim}
 * runs after a booking made through that link and makes sure the Square customer the booking landed
 * on is in the READY75 group until two days after the visit, even when the booking created a new
 * profile (another phone number) instead of the one the offer was sent to.
 */
@Service
public class ConsultationOfferService {

    private static final Logger log = LoggerFactory.getLogger(ConsultationOfferService.class);
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);

    public record Offer(boolean valid, long discountCents, long minSpendCents, Instant expiresAt, String expiresText,
                        String firstName) {
        static Offer none() {
            return new Offer(false, 0, 0, null, null, null);
        }
    }

    private final ConsultationFollowUpLinks links;
    private final ConsultationFollowUpRepository repository;
    private final PromoConfigService promoConfigService;
    private final SameDayRebookingGroupMembershipRepository membershipRepository;
    private final SquareClientProvider squareClientProvider;
    private final Clock clock;

    @Autowired
    public ConsultationOfferService(ConsultationFollowUpLinks links, ConsultationFollowUpRepository repository,
                                    PromoConfigService promoConfigService,
                                    SameDayRebookingGroupMembershipRepository membershipRepository,
                                    SquareClientProvider squareClientProvider) {
        this(links, repository, promoConfigService, membershipRepository, squareClientProvider, Clock.systemUTC());
    }

    ConsultationOfferService(ConsultationFollowUpLinks links, ConsultationFollowUpRepository repository,
                             PromoConfigService promoConfigService,
                             SameDayRebookingGroupMembershipRepository membershipRepository,
                             SquareClientProvider squareClientProvider, Clock clock) {
        this.links = links;
        this.repository = repository;
        this.promoConfigService = promoConfigService;
        this.membershipRepository = membershipRepository;
        this.squareClientProvider = squareClientProvider;
        this.clock = clock;
    }

    public Offer check(Long businessId, String token) {
        return live(businessId, token).map(l -> new Offer(true, l.terms.discountCents(),
                        l.terms.minSpendCents() == null ? 0 : l.terms.minSpendCents(), l.row.getOfferExpiresAt(),
                        l.row.getOfferExpiresAt().atZone(PACIFIC).format(DATE), l.row.getCustomerName()))
                .orElse(Offer.none());
    }

    /** {@code true} when the booking's customer now has the discount until two days after the visit. */
    public boolean claim(Long businessId, String token, String squareCustomerId, Instant visitStart) {
        Optional<Live> live = live(businessId, token);
        if (live.isEmpty() || squareCustomerId == null || squareCustomerId.isBlank() || visitStart == null) return false;
        String groupId = live.get().terms.squareCustomerGroupId();
        Instant keepUntil = visitStart.plus(Duration.ofDays(2));
        Optional<SameDayRebookingGroupMembership> membership = membershipRepository
                .findFirstByBusinessIdAndSquareCustomerIdAndGroupIdAndRemovedAtIsNullOrderByCreatedAtDesc(businessId, squareCustomerId, groupId);
        try {
            if (membership.isEmpty()) {
                // A new Square profile (booked with another phone or email): it joins the group too.
                squareClientProvider.forBusiness(businessId).addCustomerToGroup(squareCustomerId, groupId);
                membershipRepository.save(SameDayRebookingGroupMembership.builder()
                        .businessId(businessId).squareCustomerId(squareCustomerId).groupId(groupId).expiresAt(keepUntil).build());
            } else if (membership.get().getExpiresAt() == null || membership.get().getExpiresAt().isBefore(keepUntil)) {
                membership.get().setExpiresAt(keepUntil);
                membershipRepository.save(membership.get());
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Consultation offer claim for follow-up {} failed: {}", live.get().row.getId(), e.getMessage(), e);
            return false;
        }
    }

    private record Live(ConsultationFollowUp row, PromoConfigService.PromoTerms terms) {}

    private Optional<Live> live(Long businessId, String token) {
        Instant now = clock.instant();
        Optional<PromoConfigService.PromoTerms> terms = promoConfigService.get(businessId, PromoConfigService.CONSULTATION_OFFER_PROMO_CODE)
                .filter(PromoConfigService.PromoTerms::configured);
        if (terms.isEmpty()) return Optional.empty();
        return links.verifyOffer(token)
                .flatMap(id -> repository.findByIdAndBusinessId(id, businessId))
                .filter(r -> ConsultationFollowUp.STATE_SENT.equals(r.getOfferState()))
                .filter(r -> r.getOfferExpiresAt() != null && now.isBefore(r.getOfferExpiresAt()))
                .map(r -> new Live(r, terms.get()));
    }
}
