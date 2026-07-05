package com.salonreview.marketing;

import com.salonreview.marketing.AbuseBlocksRepository.RawBlock;
import com.salonreview.web.dto.AbuseBlocksDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbuseBlocksServiceTest {

    private AbuseBlocksRepository repository;
    private AbuseBlocksService service;

    @BeforeEach
    void setUp() {
        repository = mock(AbuseBlocksRepository.class);
        service = new AbuseBlocksService(repository);
    }

    @Test
    @DisplayName("returns counts and recent blocks when the schema is reachable")
    void returnsBlocksWhenAvailable() {
        when(repository.countByReasonLast24h()).thenReturn(Map.of("rate_limit_phone", 3, "honeypot", 1));
        when(repository.recent(50)).thenReturn(List.of(
                new RawBlock("booking", "rate_limit_phone", "(858) 555-0100", "1.2.3.4", Instant.parse("2026-07-05T20:00:00Z"))
        ));

        AbuseBlocksDto dto = service.blocks();

        assertThat(dto.available()).isTrue();
        assertThat(dto.countsByReasonLast24h()).containsEntry("rate_limit_phone", 3);
        assertThat(dto.recent()).hasSize(1);
        assertThat(dto.recent().get(0).reason()).isEqualTo("rate_limit_phone");
    }

    @Test
    @DisplayName("returns the unavailable DTO, not a thrown exception, when the marketing schema is unreachable")
    void unavailableWhenRepositoryThrows() {
        when(repository.countByReasonLast24h()).thenThrow(new DataAccessResourceFailureException("relation does not exist"));

        AbuseBlocksDto dto = service.blocks();

        assertThat(dto.available()).isFalse();
        assertThat(dto.recent()).isEmpty();
    }
}
