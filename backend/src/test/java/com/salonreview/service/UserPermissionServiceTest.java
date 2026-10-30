package com.salonreview.service;

import com.salonreview.domain.Permission;
import com.salonreview.domain.Role;
import com.salonreview.domain.UserPermission;
import com.salonreview.repo.UserPermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPermissionServiceTest {

    private UserPermissionRepository repo;
    private UserPermissionService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserPermissionRepository.class);
        service = new UserPermissionService(repo);
    }

    @Test
    void ownerAndManagerHaveSmsInboxWithoutAGrant() {
        assertTrue(service.has(1L, Role.OWNER, 2L, Permission.SMS_INBOX));
        assertTrue(service.has(2L, Role.MANAGER, 2L, Permission.SMS_INBOX));
    }

    @Test
    void providerNeedsAGrantInThatBusiness() {
        assertFalse(service.has(42L, Role.PROVIDER, 2L, Permission.SMS_INBOX));
        when(repo.existsByBusinessIdAndUserIdAndPermission(2L, 42L, "SMS_INBOX")).thenReturn(true);
        assertTrue(service.has(42L, Role.PROVIDER, 2L, Permission.SMS_INBOX));
        assertFalse(service.has(42L, Role.PROVIDER, 1L, Permission.SMS_INBOX));
    }

    @Test
    void adsManagerNeverGetsSmsInboxImplicitly() {
        assertFalse(service.has(5L, Role.ADS_MANAGER, 2L, Permission.SMS_INBOX));
    }

    @Test
    void effectiveCombinesRoleAndGrants() {
        when(repo.findByBusinessIdAndUserId(2L, 42L)).thenReturn(List.of(
                UserPermission.builder().businessId(2L).userId(42L).permission("SMS_INBOX").build(),
                UserPermission.builder().businessId(2L).userId(42L).permission("REMOVED_ONE").build()));
        assertEquals(1, service.effective(42L, Role.PROVIDER, 2L).size());
    }

    @Test
    void setGrantsOnceAndRevokes() {
        service.set(2L, 42L, Permission.SMS_INBOX, true, 31L);
        verify(repo).save(any(UserPermission.class));

        when(repo.existsByBusinessIdAndUserIdAndPermission(2L, 42L, "SMS_INBOX")).thenReturn(true);
        service.set(2L, 42L, Permission.SMS_INBOX, true, 31L);
        verify(repo).save(any(UserPermission.class));

        service.set(2L, 42L, Permission.SMS_INBOX, false, 31L);
        verify(repo).deleteByBusinessIdAndUserIdAndPermission(2L, 42L, "SMS_INBOX");
        verify(repo, never()).deleteById(any());
    }
}
