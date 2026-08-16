package com.salonreview.rag;

import com.salonreview.domain.KbRequest;
import com.salonreview.domain.KbRequestStatus;
import com.salonreview.domain.KbRequestTarget;
import com.salonreview.repo.KbRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link KbRequestService}: create defaults, resolve stamps, reopen clears. */
class KbRequestServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private KbRequestRepository repo;
    private KbRequestService service;

    @BeforeEach
    void setUp() {
        repo = mock(KbRequestRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new KbRequestService(repo);
    }

    @Test
    @DisplayName("create defaults to OPEN, trims question, blanks note→null")
    void createDefaults() {
        KbRequest r = service.create("  How do refunds work?  ", "   ", KbRequestTarget.KB, "manager", BUSINESS_ID);
        assertThat(r.getQuestion()).isEqualTo("How do refunds work?");
        assertThat(r.getNote()).isNull();
        assertThat(r.getTarget()).isEqualTo(KbRequestTarget.KB);
        assertThat(r.getStatus()).isEqualTo(KbRequestStatus.OPEN);
        assertThat(r.getRequestedBy()).isEqualTo("manager");
    }

    @Test
    @DisplayName("resolving stamps resolvedAt/by")
    void resolveStamps() {
        when(repo.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(KbRequest.builder().id(1L).question("q")
                .status(KbRequestStatus.OPEN).requestedBy("m").createdAt(Instant.now()).build()));

        KbRequest r = service.setStatus(1L, KbRequestStatus.RESOLVED, "owner", BUSINESS_ID).orElseThrow();

        assertThat(r.getStatus()).isEqualTo(KbRequestStatus.RESOLVED);
        assertThat(r.getResolvedBy()).isEqualTo("owner");
        assertThat(r.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("reopening clears the resolution stamp")
    void reopenClears() {
        when(repo.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(KbRequest.builder().id(1L).question("q")
                .status(KbRequestStatus.RESOLVED).resolvedBy("owner").resolvedAt(Instant.now())
                .requestedBy("m").createdAt(Instant.now()).build()));

        KbRequest r = service.setStatus(1L, KbRequestStatus.OPEN, "owner", BUSINESS_ID).orElseThrow();

        assertThat(r.getStatus()).isEqualTo(KbRequestStatus.OPEN);
        assertThat(r.getResolvedBy()).isNull();
        assertThat(r.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("delete returns false when the request doesn't exist")
    void deleteMissing() {
        when(repo.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.empty());
        assertThat(service.delete(9L, BUSINESS_ID)).isFalse();
        verify(repo, org.mockito.Mockito.never()).deleteById(any());
    }

    @Test
    @DisplayName("openCount delegates to the OPEN-status repository count")
    void openCountDelegates() {
        when(repo.countByBusinessIdAndStatus(BUSINESS_ID, KbRequestStatus.OPEN)).thenReturn(3L);
        assertThat(service.openCount(BUSINESS_ID)).isEqualTo(3L);
    }

    @Test
    @DisplayName("null target falls back to UNSURE")
    void nullTargetDefaults() {
        ArgumentCaptor<KbRequest> cap = ArgumentCaptor.forClass(KbRequest.class);
        service.create("q", null, null, "m", BUSINESS_ID);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getTarget()).isEqualTo(KbRequestTarget.UNSURE);
    }
}
