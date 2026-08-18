package com.salonreview.rag;

import com.salonreview.domain.RagAgentConfig;
import com.salonreview.repo.RagAgentConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagConfigServiceTest {

    private final RagAgentConfigRepository configs = mock(RagAgentConfigRepository.class);
    private final RagConfigService service = new RagConfigService(configs);

    @Test
    @DisplayName("2026-08-18 live incident: findActive returns empty (not throw) for a business with no "
            + "active config — RAG deliberately not yet enabled for a second business (tasks.md 7.4) is an "
            + "expected state, not a failure")
    void findActiveReturnsEmptyWhenNoRow() {
        when(configs.findByBusinessIdAndActiveTrue(2L)).thenReturn(Optional.empty());

        assertThat(service.findActive(2L)).isEmpty();
    }

    @Test
    @DisplayName("getActive still throws when no config exists — the answer-generation/SMS-draft call sites "
            + "genuinely cannot proceed without a real config")
    void getActiveStillThrowsWhenNoRow() {
        when(configs.findByBusinessIdAndActiveTrue(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getActive(2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getActiveReturnsTheRowWhenPresent() {
        RagAgentConfig cfg = RagAgentConfig.builder().businessId(1L).active(true).build();
        when(configs.findByBusinessIdAndActiveTrue(1L)).thenReturn(Optional.of(cfg));

        assertThat(service.getActive(1L)).isSameAs(cfg);
        assertThat(service.findActive(1L)).contains(cfg);
    }
}
