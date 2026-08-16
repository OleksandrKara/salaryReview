package com.salonreview.service;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Role;
import com.salonreview.domain.StaffDocument;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.StaffDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffDocumentServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private final StaffDocumentRepository documents = mock(StaffDocumentRepository.class);
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final StaffDocumentService service = new StaffDocumentService(documents, providers, users);

    @BeforeEach
    void setUp() {
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("statusFor: before today is EXPIRED")
    void statusExpired() {
        assertThat(StaffDocumentService.statusFor(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)))
                .isEqualTo(StaffDocumentService.ExpirationStatus.EXPIRED);
    }

    @Test
    @DisplayName("statusFor: within the expiring-soon window (inclusive) is EXPIRING_SOON")
    void statusExpiringSoon() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        assertThat(StaffDocumentService.statusFor(today.plusDays(StaffDocumentService.EXPIRING_SOON_DAYS), today))
                .isEqualTo(StaffDocumentService.ExpirationStatus.EXPIRING_SOON);
    }

    @Test
    @DisplayName("statusFor: beyond the expiring-soon window is OK")
    void statusOk() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        assertThat(StaffDocumentService.statusFor(today.plusDays(StaffDocumentService.EXPIRING_SOON_DAYS + 1), today))
                .isEqualTo(StaffDocumentService.ExpirationStatus.OK);
    }

    @Test
    @DisplayName("create rejects neither providerId nor appUserId given")
    void createRejectsNeitherPerson() {
        assertThatThrownBy(() -> service.create(null, null, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[]{1}, "owner", BUSINESS_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create rejects both providerId and appUserId given")
    void createRejectsBothPersons() {
        assertThatThrownBy(() -> service.create(1L, 2L, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[]{1}, "owner", BUSINESS_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create rejects a providerId that doesn't exist")
    void createRejectsUnknownProvider() {
        when(providers.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(1L, null, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[]{1}, "owner", BUSINESS_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create rejects an appUserId that isn't a MANAGER (e.g. an owner or provider login)")
    void createRejectsNonManagerAppUser() {
        when(users.findByIdAndBusinessId(5L, BUSINESS_ID)).thenReturn(Optional.of(AppUser.builder().id(5L).role(Role.OWNER).build()));

        assertThatThrownBy(() -> service.create(null, 5L, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[]{1}, "owner", BUSINESS_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create succeeds for a real provider")
    void createSucceedsForProvider() {
        when(providers.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(Provider.builder().id(1L).businessId(BUSINESS_ID).build()));

        StaffDocument saved = service.create(1L, null, "License", "Cosmetology — CA",
                LocalDate.of(2027, 6, 1), "license.pdf", "application/pdf", new byte[]{1, 2, 3}, "owner", BUSINESS_ID);

        assertThat(saved.getProviderId()).isEqualTo(1L);
        assertThat(saved.getAppUserId()).isNull();
        assertThat(saved.getDocumentType()).isEqualTo("License");
        assertThat(saved.getLabel()).isEqualTo("Cosmetology — CA");
        assertThat(saved.getExpirationDate()).isEqualTo(LocalDate.of(2027, 6, 1));
    }

    @Test
    @DisplayName("create succeeds for a real MANAGER app_user")
    void createSucceedsForManager() {
        when(users.findByIdAndBusinessId(5L, BUSINESS_ID)).thenReturn(Optional.of(AppUser.builder().id(5L).role(Role.MANAGER).build()));

        StaffDocument saved = service.create(null, 5L, "NDA", null,
                LocalDate.of(2027, 1, 1), "nda.pdf", "application/pdf", new byte[]{1}, "owner", BUSINESS_ID);

        assertThat(saved.getAppUserId()).isEqualTo(5L);
        assertThat(saved.getProviderId()).isNull();
        assertThat(saved.getLabel()).isNull(); // blank/omitted label stays null, not ""
    }

    @Test
    @DisplayName("create rejects an empty file")
    void createRejectsEmptyFile() {
        when(providers.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(Provider.builder().id(1L).businessId(BUSINESS_ID).build()));

        assertThatThrownBy(() -> service.create(1L, null, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[0], "owner", BUSINESS_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete returns false for a non-existent id, doesn't call repository.deleteById")
    void deleteMissingReturnsFalse() {
        when(documents.findByIdAndBusinessId(99L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThat(service.delete(99L, BUSINESS_ID)).isFalse();
    }

    @Test
    @DisplayName("listAll delegates straight to the business-scoped, soonest-expiring-first query")
    void listAllDelegates() {
        StaffDocument d = StaffDocument.builder().id(1L).build();
        when(documents.findAllByBusinessIdOrderByExpirationDateAsc(BUSINESS_ID)).thenReturn(List.of(d));

        assertThat(service.listAll(BUSINESS_ID)).containsExactly(d);
    }

    @Test
    @DisplayName("update returns empty for a non-existent id")
    void updateMissingReturnsEmpty() {
        when(documents.findByIdAndBusinessId(99L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThat(service.update(99L, LocalDate.now(), null, null, BUSINESS_ID)).isEmpty();
    }

    @Test
    @DisplayName("update only touches fields that are non-null, leaving the rest as-is")
    void updateOnlyTouchesGivenFields() {
        StaffDocument existing = StaffDocument.builder().id(1L).documentType("License")
                .label("Cosmetology — CA").expirationDate(LocalDate.of(2027, 1, 1)).build();
        when(documents.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(existing));

        StaffDocument updated = service.update(1L, LocalDate.of(2099, 1, 1), null, null, BUSINESS_ID).orElseThrow();

        assertThat(updated.getExpirationDate()).isEqualTo(LocalDate.of(2099, 1, 1));
        assertThat(updated.getDocumentType()).isEqualTo("License"); // untouched
        assertThat(updated.getLabel()).isEqualTo("Cosmetology — CA"); // untouched
    }

    @Test
    @DisplayName("update renames documentType/label; an empty label clears it")
    void updateRenamesAndClearsLabel() {
        StaffDocument existing = StaffDocument.builder().id(1L).documentType("License")
                .label("Cosmetology — CA").expirationDate(LocalDate.of(2027, 1, 1)).build();
        when(documents.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(existing));

        StaffDocument updated = service.update(1L, null, "Insurance", "", BUSINESS_ID).orElseThrow();

        assertThat(updated.getDocumentType()).isEqualTo("Insurance");
        assertThat(updated.getLabel()).isNull();
        assertThat(updated.getExpirationDate()).isEqualTo(LocalDate.of(2027, 1, 1)); // untouched
    }

    @Test
    @DisplayName("update rejects a blank documentType")
    void updateRejectsBlankDocumentType() {
        when(documents.findByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(Optional.of(StaffDocument.builder().id(1L).build()));

        assertThatThrownBy(() -> service.update(1L, null, "  ", null, BUSINESS_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("listForProvider delegates to the provider-scoped query")
    void listForProviderDelegates() {
        StaffDocument d = StaffDocument.builder().id(1L).providerId(10L).build();
        when(documents.findAllByProviderIdOrderByExpirationDateAsc(10L)).thenReturn(List.of(d));

        assertThat(service.listForProvider(10L)).containsExactly(d);
    }

    @Test
    @DisplayName("listForManager delegates to the manager-scoped query")
    void listForManagerDelegates() {
        StaffDocument d = StaffDocument.builder().id(2L).appUserId(20L).build();
        when(documents.findAllByAppUserIdOrderByExpirationDateAsc(20L)).thenReturn(List.of(d));

        assertThat(service.listForManager(20L)).containsExactly(d);
    }
}
