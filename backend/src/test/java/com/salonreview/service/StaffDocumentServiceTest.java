package com.salonreview.service;

import com.salonreview.domain.AppUser;
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
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[]{1}, "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create rejects both providerId and appUserId given")
    void createRejectsBothPersons() {
        assertThatThrownBy(() -> service.create(1L, 2L, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[]{1}, "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create rejects a providerId that doesn't exist")
    void createRejectsUnknownProvider() {
        when(providers.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(1L, null, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[]{1}, "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create rejects an appUserId that isn't a MANAGER (e.g. an owner or provider login)")
    void createRejectsNonManagerAppUser() {
        when(users.findById(5L)).thenReturn(Optional.of(AppUser.builder().id(5L).role(Role.OWNER).build()));

        assertThatThrownBy(() -> service.create(null, 5L, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[]{1}, "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create succeeds for a real provider")
    void createSucceedsForProvider() {
        when(providers.existsById(1L)).thenReturn(true);

        StaffDocument saved = service.create(1L, null, "License", "Cosmetology — CA",
                LocalDate.of(2027, 6, 1), "license.pdf", "application/pdf", new byte[]{1, 2, 3}, "owner");

        assertThat(saved.getProviderId()).isEqualTo(1L);
        assertThat(saved.getAppUserId()).isNull();
        assertThat(saved.getDocumentType()).isEqualTo("License");
        assertThat(saved.getLabel()).isEqualTo("Cosmetology — CA");
        assertThat(saved.getExpirationDate()).isEqualTo(LocalDate.of(2027, 6, 1));
    }

    @Test
    @DisplayName("create succeeds for a real MANAGER app_user")
    void createSucceedsForManager() {
        when(users.findById(5L)).thenReturn(Optional.of(AppUser.builder().id(5L).role(Role.MANAGER).build()));

        StaffDocument saved = service.create(null, 5L, "NDA", null,
                LocalDate.of(2027, 1, 1), "nda.pdf", "application/pdf", new byte[]{1}, "owner");

        assertThat(saved.getAppUserId()).isEqualTo(5L);
        assertThat(saved.getProviderId()).isNull();
        assertThat(saved.getLabel()).isNull(); // blank/omitted label stays null, not ""
    }

    @Test
    @DisplayName("create rejects an empty file")
    void createRejectsEmptyFile() {
        when(providers.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(1L, null, "Contract", null,
                LocalDate.now().plusYears(1), "f.pdf", "application/pdf", new byte[0], "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete returns false for a non-existent id, doesn't call repository.deleteById")
    void deleteMissingReturnsFalse() {
        when(documents.existsById(99L)).thenReturn(false);

        assertThat(service.delete(99L)).isFalse();
    }

    @Test
    @DisplayName("listAll delegates straight to the soonest-expiring-first query")
    void listAllDelegates() {
        StaffDocument d = StaffDocument.builder().id(1L).build();
        when(documents.findAllByOrderByExpirationDateAsc()).thenReturn(List.of(d));

        assertThat(service.listAll()).containsExactly(d);
    }

    @Test
    @DisplayName("update returns empty for a non-existent id")
    void updateMissingReturnsEmpty() {
        when(documents.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.update(99L, LocalDate.now(), null, null)).isEmpty();
    }

    @Test
    @DisplayName("update only touches fields that are non-null, leaving the rest as-is")
    void updateOnlyTouchesGivenFields() {
        StaffDocument existing = StaffDocument.builder().id(1L).documentType("License")
                .label("Cosmetology — CA").expirationDate(LocalDate.of(2027, 1, 1)).build();
        when(documents.findById(1L)).thenReturn(Optional.of(existing));

        StaffDocument updated = service.update(1L, LocalDate.of(2099, 1, 1), null, null).orElseThrow();

        assertThat(updated.getExpirationDate()).isEqualTo(LocalDate.of(2099, 1, 1));
        assertThat(updated.getDocumentType()).isEqualTo("License"); // untouched
        assertThat(updated.getLabel()).isEqualTo("Cosmetology — CA"); // untouched
    }

    @Test
    @DisplayName("update renames documentType/label; an empty label clears it")
    void updateRenamesAndClearsLabel() {
        StaffDocument existing = StaffDocument.builder().id(1L).documentType("License")
                .label("Cosmetology — CA").expirationDate(LocalDate.of(2027, 1, 1)).build();
        when(documents.findById(1L)).thenReturn(Optional.of(existing));

        StaffDocument updated = service.update(1L, null, "Insurance", "").orElseThrow();

        assertThat(updated.getDocumentType()).isEqualTo("Insurance");
        assertThat(updated.getLabel()).isNull();
        assertThat(updated.getExpirationDate()).isEqualTo(LocalDate.of(2027, 1, 1)); // untouched
    }

    @Test
    @DisplayName("update rejects a blank documentType")
    void updateRejectsBlankDocumentType() {
        when(documents.findById(1L)).thenReturn(Optional.of(StaffDocument.builder().id(1L).build()));

        assertThatThrownBy(() -> service.update(1L, null, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
