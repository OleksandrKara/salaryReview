package com.salonreview.sop;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.domain.Sop;
import com.salonreview.domain.SopAcknowledgment;
import com.salonreview.domain.SopAudience;
import com.salonreview.domain.SopStatus;
import com.salonreview.domain.SopVersion;
import com.salonreview.domain.SopVersionStatus;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.SopAcknowledgmentRepository;
import com.salonreview.repo.SopRepository;
import com.salonreview.repo.SopVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SopService}: authoring/publish, audience-filtered reads, acknowledge, roster. */
class SopServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private SopRepository sops;
    private SopVersionRepository versions;
    private SopAcknowledgmentRepository acks;
    private AppUserRepository users;
    private SopService service;

    @BeforeEach
    void setUp() {
        sops = mock(SopRepository.class);
        versions = mock(SopVersionRepository.class);
        acks = mock(SopAcknowledgmentRepository.class);
        users = mock(AppUserRepository.class);
        when(sops.save(any())).thenAnswer(inv -> {
            Sop s = inv.getArgument(0);
            if (s.getId() == null) s.setId(1L);
            return s;
        });
        when(versions.save(any())).thenAnswer(inv -> {
            SopVersion v = inv.getArgument(0);
            if (v.getId() == null) v.setId(100L);
            return v;
        });
        service = new SopService(sops, versions, acks, users);
    }

    private Sop sop(SopAudience audience, SopStatus status, Long currentVersionId) {
        return Sop.builder().id(1L).title("Cleaning").category("Hygiene").audience(audience)
                .status(status).currentVersionId(currentVersionId).createdBy("owner").businessId(BUSINESS_ID).build();
    }

    @Test
    @DisplayName("create makes a SOP plus a version-1 draft")
    void createMakesV1Draft() {
        service.create("Cleaning", null, "Hygiene", SopAudience.PROVIDER, null, "wash hands", null, "owner", BUSINESS_ID);
        ArgumentCaptor<SopVersion> cap = ArgumentCaptor.forClass(SopVersion.class);
        verify(versions).save(cap.capture());
        assertThat(cap.getValue().getVersionNumber()).isEqualTo(1);
        assertThat(cap.getValue().getStatus()).isEqualTo(SopVersionStatus.DRAFT);
    }

    @Test
    @DisplayName("publish sets current version + marks PUBLISHED")
    void publishSetsCurrent() {
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(SopAudience.PROVIDER, SopStatus.ACTIVE, null)));
        when(versions.findById(100L)).thenReturn(Optional.of(SopVersion.builder().id(100L).sopId(1L)
                .versionNumber(1).body("b").status(SopVersionStatus.DRAFT).createdBy("owner").build()));

        Sop out = service.publish(1L, 100L, BUSINESS_ID).orElseThrow();

        assertThat(out.getCurrentVersionId()).isEqualTo(100L);
        ArgumentCaptor<SopVersion> cap = ArgumentCaptor.forClass(SopVersion.class);
        verify(versions).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(SopVersionStatus.PUBLISHED);
    }

    @Test
    @DisplayName("re-publishing an already-published version is rejected")
    void rePublishRejected() {
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(SopAudience.PROVIDER, SopStatus.ACTIVE, 100L)));
        when(versions.findById(100L)).thenReturn(Optional.of(SopVersion.builder().id(100L).sopId(1L)
                .versionNumber(1).body("b").status(SopVersionStatus.PUBLISHED).createdBy("owner").build()));

        assertThatThrownBy(() -> service.publish(1L, 100L, BUSINESS_ID))
                .isInstanceOf(SopService.AlreadyPublishedException.class);
    }

    @Test
    @DisplayName("new draft is max+1 and doesn't change the live version")
    void addVersionMaxPlusOne() {
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(SopAudience.PROVIDER, SopStatus.ACTIVE, 100L)));
        when(versions.findTopBySopIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(
                SopVersion.builder().id(100L).sopId(1L).versionNumber(2).status(SopVersionStatus.PUBLISHED).build()));

        service.addVersion(1L, "v3 body", null, null, null, "owner", BUSINESS_ID);

        ArgumentCaptor<SopVersion> cap = ArgumentCaptor.forClass(SopVersion.class);
        verify(versions).save(cap.capture());
        assertThat(cap.getValue().getVersionNumber()).isEqualTo(3);
        assertThat(cap.getValue().getStatus()).isEqualTo(SopVersionStatus.DRAFT);
        verify(sops, never()).save(any()); // current unchanged
    }

    @Test
    @DisplayName("addVersion persists the change note, blanking an empty one to null")
    void addVersionChangeNote() {
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(SopAudience.PROVIDER, SopStatus.ACTIVE, 100L)));
        when(versions.findTopBySopIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());

        service.addVersion(1L, "v1 body", null, "Added late-arrival policy", "  ", "owner", BUSINESS_ID);

        ArgumentCaptor<SopVersion> cap = ArgumentCaptor.forClass(SopVersion.class);
        verify(versions).save(cap.capture());
        assertThat(cap.getValue().getChangeNote()).isEqualTo("Added late-arrival policy");
        assertThat(cap.getValue().getChangeNoteRu()).isNull();
    }

    @Test
    @DisplayName("audience filtering: provider sees PROVIDER/BOTH published+active; manager-only and draft excluded")
    void audienceFiltering() {
        Sop providerSop = Sop.builder().id(1L).title("a").category("c").audience(SopAudience.PROVIDER)
                .status(SopStatus.ACTIVE).currentVersionId(100L).createdBy("o").businessId(BUSINESS_ID).build();
        Sop bothSop = Sop.builder().id(2L).title("b").category("c").audience(SopAudience.BOTH)
                .status(SopStatus.ACTIVE).currentVersionId(200L).createdBy("o").businessId(BUSINESS_ID).build();
        Sop managerSop = Sop.builder().id(3L).title("c").category("c").audience(SopAudience.MANAGER)
                .status(SopStatus.ACTIVE).currentVersionId(300L).createdBy("o").businessId(BUSINESS_ID).build();
        Sop draftSop = Sop.builder().id(4L).title("d").category("c").audience(SopAudience.PROVIDER)
                .status(SopStatus.ACTIVE).currentVersionId(null).createdBy("o").businessId(BUSINESS_ID).build();
        when(sops.findByBusinessIdAndStatusOrderByPriorityAscCategoryAscTitleAsc(BUSINESS_ID, SopStatus.ACTIVE))
                .thenReturn(List.of(providerSop, bothSop, managerSop, draftSop));
        when(versions.findById(anyLong())).thenReturn(Optional.of(
                SopVersion.builder().id(100L).sopId(1L).versionNumber(1).body("x").status(SopVersionStatus.PUBLISHED).build()));
        when(acks.findBySopVersionIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());

        List<SopService.SopListItem> seen = service.list(Role.PROVIDER, 7L, BUSINESS_ID);

        assertThat(seen).extracting(i -> i.sop().getId()).containsExactly(1L, 2L); // provider + both, not manager, not draft
    }

    @Test
    @DisplayName("acknowledge is idempotent and audience-gated; nothing-to-ack rejected")
    void acknowledgeRules() {
        // out of audience: provider acking a manager-only SOP
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(SopAudience.MANAGER, SopStatus.ACTIVE, 100L)));
        assertThatThrownBy(() -> service.acknowledge(1L, 7L, Role.PROVIDER, BUSINESS_ID))
                .isInstanceOf(SopService.OutOfAudienceException.class);

        // nothing to acknowledge: no published version
        when(sops.findByIdAndBusinessId(2L, BUSINESS_ID)).thenReturn(Optional.of(Sop.builder().id(2L).title("t").category("c")
                .audience(SopAudience.PROVIDER).status(SopStatus.ACTIVE).currentVersionId(null).createdBy("o").businessId(BUSINESS_ID).build()));
        assertThatThrownBy(() -> service.acknowledge(2L, 7L, Role.PROVIDER, BUSINESS_ID))
                .isInstanceOf(SopService.NothingToAcknowledgeException.class);

        // idempotent: already acknowledged → no new row
        Sop ok = sop(SopAudience.PROVIDER, SopStatus.ACTIVE, 100L);
        when(sops.findByIdAndBusinessId(3L, BUSINESS_ID)).thenReturn(Optional.of(ok));
        when(acks.existsBySopVersionIdAndUserId(100L, 7L)).thenReturn(true);
        when(versions.findById(100L)).thenReturn(Optional.of(SopVersion.builder().id(100L).build()));
        when(acks.findBySopVersionIdAndUserId(100L, 7L)).thenReturn(Optional.of(
                SopAcknowledgment.builder().sopVersionId(100L).userId(7L).acknowledgedAt(Instant.now()).build()));
        service.acknowledge(3L, 7L, Role.PROVIDER, BUSINESS_ID);
        verify(acks, never()).save(any());
    }

    @Test
    @DisplayName("republish resets acknowledgment: ack against the old version doesn't satisfy the new current")
    void republishResetsAck() {
        Sop s = sop(SopAudience.PROVIDER, SopStatus.ACTIVE, 300L); // current is v3 (id 300)
        when(versions.findById(300L)).thenReturn(Optional.of(SopVersion.builder().id(300L).build()));
        when(acks.findBySopVersionIdAndUserId(300L, 7L)).thenReturn(Optional.empty()); // only acked v2, not v3

        assertThat(service.item(s, 7L).acknowledged()).isFalse();
    }

    @Test
    @DisplayName("getVisible: owner sees a draft-only SOP that a manager/provider would not")
    void getVisibleOwnerSeesEverything() {
        Sop draftOnly = sop(SopAudience.PROVIDER, SopStatus.ACTIVE, null);
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(draftOnly));

        assertThat(service.getVisible(1L, Role.OWNER, 7L, BUSINESS_ID)).isPresent();
        assertThat(service.getVisible(1L, Role.PROVIDER, 7L, BUSINESS_ID)).isEmpty();
    }

    @Test
    @DisplayName("getVisible: manager/provider only see an ACTIVE, published SOP whose audience includes their role")
    void getVisibleAudienceGated() {
        Sop providerSop = sop(SopAudience.PROVIDER, SopStatus.ACTIVE, 100L);
        Sop managerOnly = Sop.builder().id(2L).title("m").category("c").audience(SopAudience.MANAGER)
                .status(SopStatus.ACTIVE).currentVersionId(200L).createdBy("o").businessId(BUSINESS_ID).build();
        Sop archived = Sop.builder().id(3L).title("a").category("c").audience(SopAudience.PROVIDER)
                .status(SopStatus.ARCHIVED).currentVersionId(300L).createdBy("o").businessId(BUSINESS_ID).build();
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(providerSop));
        when(sops.findByIdAndBusinessId(2L, BUSINESS_ID)).thenReturn(Optional.of(managerOnly));
        when(sops.findByIdAndBusinessId(3L, BUSINESS_ID)).thenReturn(Optional.of(archived));
        when(sops.findByIdAndBusinessId(99L, BUSINESS_ID)).thenReturn(Optional.empty());
        when(versions.findById(100L)).thenReturn(Optional.of(
                SopVersion.builder().id(100L).sopId(1L).versionNumber(1).body("x").status(SopVersionStatus.PUBLISHED).build()));
        when(acks.findBySopVersionIdAndUserId(100L, 7L)).thenReturn(Optional.empty());

        assertThat(service.getVisible(1L, Role.PROVIDER, 7L, BUSINESS_ID)).isPresent();
        assertThat(service.getVisible(2L, Role.PROVIDER, 7L, BUSINESS_ID)).isEmpty(); // wrong audience
        assertThat(service.getVisible(3L, Role.PROVIDER, 7L, BUSINESS_ID)).isEmpty(); // archived
        assertThat(service.getVisible(99L, Role.PROVIDER, 7L, BUSINESS_ID)).isEmpty(); // doesn't exist
    }

    @Test
    @DisplayName("roster lists the audience's active users with correct ack flags")
    void roster() {
        when(sops.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(sop(SopAudience.BOTH, SopStatus.ACTIVE, 100L)));
        when(users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(eq(BUSINESS_ID), any())).thenReturn(List.of(
                AppUser.builder().id(10L).username("mgr").role(Role.MANAGER).active(true).passwordHash("x").build(),
                AppUser.builder().id(11L).username("prov").role(Role.PROVIDER).active(true).passwordHash("x").build()));
        when(acks.findBySopVersionId(100L)).thenReturn(List.of(
                SopAcknowledgment.builder().sopVersionId(100L).userId(10L).acknowledgedAt(Instant.now()).build()));

        List<SopService.RosterEntry> roster = service.roster(1L, BUSINESS_ID);

        assertThat(roster).hasSize(2);
        assertThat(roster).filteredOn(SopService.RosterEntry::acknowledged).extracting(SopService.RosterEntry::userId)
                .containsExactly(10L);
    }
}
