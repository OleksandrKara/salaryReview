package com.salonreview.sms;

import com.salonreview.domain.ProviderVisit;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.SquarePaymentMirrorRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import com.salonreview.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * "VIP rebooking perk" (owner request 2026-10-01): artists hand every client a card with a QR code
 * to akluxnails.com/vip after their visit. The client types their phone number; this decides
 * whether they get the {@link PromoConfigService#VIP_PROMO_CODE} offer ($10 off, same Square
 * discount group as REBOOK10) for booking their next visit today, for a date within
 * {@link #WINDOW_DAYS} days.
 *
 * <p>Eligible only if this phone number's Square customer had a visit today (salon time): a
 * non-cancelled booking starting today (live Square, so a client still in the chair counts), or,
 * for walk-ins, a completed payment today. The answer is deliberately just yes/no, never any detail
 * about the booking, since anyone can type any number on that page.
 *
 * <p>The page personalizes from the result: first name, today's artist (offered again: same-artist
 * clients came back 79% vs 62% after switching, see AK.LUX.NAILS_visit2_churn_deepdive.md) and
 * whether this was the client's first visit (no earlier {@code provider_visit} rows).
 */
@Service
public class VipRebookEligibilityService {
    private static final Logger log = LoggerFactory.getLogger(VipRebookEligibilityService.class);
    static final ZoneId SALON_ZONE = SameDayRebookingTriggerService.SALON_ZONE;
    /** Owner decision 2026-10-01: the perk only covers a next visit within 4 weeks. */
    static final int WINDOW_DAYS = 28;

    public record Result(boolean eligible, String reason, String squareCustomerId, String givenName,
                         String technicianName, String teamMemberId, boolean newClient,
                         long expEpochSeconds, String signature, long latestStartEpochSeconds) {
        public static Result no(String reason) {
            return new Result(false, reason, null, null, null, null, false, 0, null, 0);
        }
    }

    private final SquareClientProvider squareClientProvider;
    private final SquarePaymentMirrorRepository payments;
    private final ProviderVisitRepository visits;
    private final ProviderRepository providers;
    private final RebookingPromoSigner signer;
    private final PromoConfigService promoConfigService;
    private final Clock clock;

    @Autowired
    public VipRebookEligibilityService(SquareClientProvider squareClientProvider, SquarePaymentMirrorRepository payments,
                                       ProviderVisitRepository visits, ProviderRepository providers,
                                       RebookingPromoSigner signer, PromoConfigService promoConfigService) {
        this(squareClientProvider, payments, visits, providers, signer, promoConfigService, Clock.system(SALON_ZONE));
    }

    /** Test-only: fixed clock. */
    VipRebookEligibilityService(SquareClientProvider squareClientProvider, SquarePaymentMirrorRepository payments,
                                ProviderVisitRepository visits, ProviderRepository providers,
                                RebookingPromoSigner signer, PromoConfigService promoConfigService, Clock clock) {
        this.squareClientProvider = squareClientProvider;
        this.payments = payments;
        this.visits = visits;
        this.providers = providers;
        this.signer = signer;
        this.promoConfigService = promoConfigService;
        this.clock = clock;
    }

    public Result check(Long businessId, String rawPhone) {
        if (promoConfigService.get(businessId, PromoConfigService.VIP_PROMO_CODE).isEmpty()) {
            return Result.no("not_configured");
        }
        String phone = PhoneNumbers.normalize(rawPhone);
        if (phone == null || phone.isBlank()) {
            return Result.no("invalid_phone");
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(SALON_ZONE).toLocalDate();
        Instant startOfToday = today.atStartOfDay(SALON_ZONE).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(SALON_ZONE).toInstant();

        SquareClient square = squareClientProvider.forBusiness(businessId);
        List<String> customerIds = square.customerIdsForPhone(phone);
        if (customerIds.isEmpty()) {
            return Result.no("no_visit_today"); // same answer as "known but no visit": reveal nothing
        }

        String customerId = null;
        String teamMemberId = null;
        for (String id : customerIds) {
            Optional<SquareClient.Booking> todays = square.bookingsForCustomer(id, startOfToday).stream()
                    .filter(SquareBookingFilters::didHappen)
                    .filter(b -> startsBetween(b.startAt(), startOfToday, startOfTomorrow))
                    .findFirst();
            if (todays.isPresent()) {
                customerId = id;
                teamMemberId = todays.get().appointmentSegments() == null ? null
                        : todays.get().appointmentSegments().stream()
                        .map(SquareClient.AppointmentSegment::teamMemberId).filter(t -> t != null && !t.isBlank())
                        .findFirst().orElse(null);
                break;
            }
        }
        if (customerId == null) {
            customerId = payments.findByBusinessIdAndCreatedAtBetween(businessId, startOfToday, startOfTomorrow).stream()
                    .filter(p -> "COMPLETED".equals(p.getStatus()) && customerIds.contains(p.getSquareCustomerId()))
                    .map(p -> p.getSquareCustomerId())
                    .findFirst().orElse(null);
        }
        if (customerId == null) {
            return Result.no("no_visit_today");
        }

        String givenName = Names.capitalizeFirst(square.customerGivenNames(List.of(customerId)).getOrDefault(customerId, null));
        String technicianName = teamMemberId == null ? null
                : providers.findBySquareTeamMemberIdAndBusinessId(teamMemberId, businessId)
                .map(p -> Names.firstNameOnly(p.getDisplayName())).orElse(null);
        boolean newClient = visits.findByBusinessIdAndCustomerIdOrderByServiceDateDesc(businessId, customerId, PageRequest.of(0, 5))
                .stream().map(ProviderVisit::getServiceDate).noneMatch(d -> d.isBefore(today));

        long exp = startOfTomorrow.getEpochSecond();
        long latestStart = latestStartFor(now).getEpochSecond();
        log.info("VIP rebook perk: eligible customer {} (new client: {})", customerId, newClient);
        return new Result(true, null, customerId, givenName, technicianName, teamMemberId, newClient,
                exp, signer.sign(PromoConfigService.VIP_PROMO_CODE, exp), latestStart);
    }

    /** Latest allowed appointment start for a VIP10 booking made on {@code bookedOn}'s salon day:
     * end of day {@link #WINDOW_DAYS}. Also used by enrollment, which re-checks it server-side. */
    public static Instant latestStartFor(Instant bookedOn) {
        LocalDate day = bookedOn.atZone(SALON_ZONE).toLocalDate();
        return day.plusDays(WINDOW_DAYS + 1).atStartOfDay(SALON_ZONE).toInstant();
    }

    private static boolean startsBetween(String startAt, Instant from, Instant to) {
        if (startAt == null) return false;
        try {
            Instant s = Instant.parse(startAt);
            return !s.isBefore(from) && s.isBefore(to);
        } catch (Exception e) {
            return false;
        }
    }
}
