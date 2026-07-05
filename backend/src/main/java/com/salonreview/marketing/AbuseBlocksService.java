package com.salonreview.marketing;

import com.salonreview.web.dto.AbuseBlocksDto;
import com.salonreview.web.dto.AbuseBlocksDto.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AbuseBlocksService {

    private static final Logger log = LoggerFactory.getLogger(AbuseBlocksService.class);
    private static final int RECENT_LIMIT = 50;

    private final AbuseBlocksRepository repository;

    public AbuseBlocksService(AbuseBlocksRepository repository) {
        this.repository = repository;
    }

    /** Never throws: same "this app's health must never depend on the other service's schema"
     * guarantee as MarketingDashboardService.dashboard.
     */
    public AbuseBlocksDto blocks() {
        try {
            List<Block> recent = repository.recent(RECENT_LIMIT).stream()
                    .map(r -> new Block(r.endpoint(), r.reason(), r.phoneNumber(), r.ipAddress(), r.occurredAt()))
                    .collect(Collectors.toList());
            return new AbuseBlocksDto(true, repository.countByReasonLast24h(), recent);
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building abuse blocks summary", ex);
            return AbuseBlocksDto.unavailable();
        }
    }
}
