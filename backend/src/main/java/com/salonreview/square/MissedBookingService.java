package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.MissedBooking;
import com.salonreview.repo.MissedBookingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Manager-logged "we had nowhere to book this customer" entries — see V121 /
 * {@link MissedBooking}'s own doc. Same business-scoping convention as {@code RedoService}.
 */
@Service
public class MissedBookingService {

    private final MissedBookingRepository missedBookings;
    private final CurrentBusinessContext currentBusinessContext;

    public MissedBookingService(MissedBookingRepository missedBookings, CurrentBusinessContext currentBusinessContext) {
        this.missedBookings = missedBookings;
        this.currentBusinessContext = currentBusinessContext;
    }

    public record CreateRequest(LocalDate requestedDate, LocalTime requestedTime, BigDecimal estimatedRevenue,
                                 String serviceName) {}

    public record MissedBookingView(Long id, String requestedDate, String requestedTime, BigDecimal estimatedRevenue,
                                     String serviceName, String createdBy, String createdAt) {}

    public List<MissedBookingView> list() {
        return missedBookings.findAllByBusinessIdOrderByRequestedDateDescCreatedAtDesc(currentBusinessContext.id())
                .stream().map(MissedBookingService::toView).toList();
    }

    @Transactional
    public MissedBookingView create(CreateRequest req, String by) {
        if (req.requestedDate() == null || req.estimatedRevenue() == null || req.estimatedRevenue().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "a requested date and a positive estimated revenue are required");
        }
        MissedBooking saved = missedBookings.save(MissedBooking.builder()
                .businessId(currentBusinessContext.id())
                .requestedDate(req.requestedDate())
                .requestedTime(req.requestedTime())
                .estimatedRevenue(req.estimatedRevenue())
                .serviceName(blankToNull(req.serviceName()))
                .createdBy(by)
                .build());
        return toView(saved);
    }

    @Transactional
    public void delete(Long id) {
        MissedBooking row = missedBookings.findByIdAndBusinessId(id, currentBusinessContext.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such missed booking"));
        missedBookings.delete(row);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static MissedBookingView toView(MissedBooking m) {
        return new MissedBookingView(m.getId(), m.getRequestedDate().toString(),
                m.getRequestedTime() == null ? null : m.getRequestedTime().toString(), m.getEstimatedRevenue(),
                m.getServiceName(), m.getCreatedBy(), m.getCreatedAt().toString());
    }
}
