package com.salonreview.service;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.BusinessMembership;
import com.salonreview.domain.Role;
import com.salonreview.domain.SmsAutomation;
import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsAutomationRepository;
import com.salonreview.repo.TelegramNotificationConfigRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.sms.SmsAutomationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BusinessProvisioningServiceTest {

    private BusinessRepository businesses;
    private AppUserRepository users;
    private BusinessMembershipRepository memberships;
    private PasswordEncoder encoder;
    private TelegramNotificationConfigRepository telegramConfigs;
    private TwilioSmsConfigRepository twilioConfigs;
    private SmsAutomationRepository smsAutomations;
    private BusinessProvisioningService service;

    @BeforeEach
    void setUp() {
        businesses = mock(BusinessRepository.class);
        users = mock(AppUserRepository.class);
        memberships = mock(BusinessMembershipRepository.class);
        encoder = mock(PasswordEncoder.class);
        telegramConfigs = mock(TelegramNotificationConfigRepository.class);
        twilioConfigs = mock(TwilioSmsConfigRepository.class);
        smsAutomations = mock(SmsAutomationRepository.class);
        service = new BusinessProvisioningService(businesses, users, memberships, encoder, telegramConfigs, twilioConfigs, smsAutomations);

        when(businesses.findByShortCode(any())).thenReturn(Optional.empty());
        when(users.findByUsername(any())).thenReturn(Optional.empty());
        when(encoder.encode(any())).thenReturn("hashed");
        when(businesses.save(any())).thenAnswer(inv -> {
            Business b = inv.getArgument(0);
            b.setId(2L);
            return b;
        });
        when(users.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            u.setId(20L);
            return u;
        });
    }

    @Test
    @DisplayName("creates the business, its first OWNER, and the membership row, all linked")
    void createsBusinessOwnerAndMembership() {
        Business created = service.create("AK PMU", "AnnaKaraPMU", "America/Los_Angeles",
                "annakarapmu", "s3cret!");

        assertThat(created.getId()).isEqualTo(2L);
        assertThat(created.getName()).isEqualTo("AK PMU");
        assertThat(created.getShortCode()).isEqualTo("annakarapmu"); // lowercased
        assertThat(created.isActive()).isTrue();

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getBusinessId()).isEqualTo(2L);
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("annakarapmu");
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.OWNER);
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hashed");

        ArgumentCaptor<BusinessMembership> membershipCaptor = ArgumentCaptor.forClass(BusinessMembership.class);
        verify(memberships).save(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue().getBusinessId()).isEqualTo(2L);
        assertThat(membershipCaptor.getValue().getUserId()).isEqualTo(20L);
        assertThat(membershipCaptor.getValue().getRole()).isEqualTo(Role.OWNER);
    }

    @Test
    @DisplayName("2026-08-18 live incident: seeds an empty (all-null-credential) telegram + twilio config row "
            + "for the new business — without this, the owner's own Settings > Telegram/SMS pages 500 the "
            + "instant they're opened, since those services assume their row already exists")
    void seedsEmptyTelegramAndTwilioConfigRows() {
        service.create("AK PMU", "AnnaKaraPMU", "America/Los_Angeles", "annakarapmu", "s3cret!");

        ArgumentCaptor<TelegramNotificationConfig> telegramCaptor = ArgumentCaptor.forClass(TelegramNotificationConfig.class);
        verify(telegramConfigs).save(telegramCaptor.capture());
        assertThat(telegramCaptor.getValue().getBusinessId()).isEqualTo(2L);
        assertThat(telegramCaptor.getValue().getBotToken()).isNull();
        assertThat(telegramCaptor.getValue().getChatId()).isNull();

        ArgumentCaptor<TwilioSmsConfig> twilioCaptor = ArgumentCaptor.forClass(TwilioSmsConfig.class);
        verify(twilioConfigs).save(twilioCaptor.capture());
        assertThat(twilioCaptor.getValue().getBusinessId()).isEqualTo(2L);
        assertThat(twilioCaptor.getValue().isConfigured()).isFalse();
    }

    @Test
    @DisplayName("2026-08-18 live incident: seeds an explicit enabled=false row for every known SMS "
            + "automation — without this, a missing row used to fail *open* in SmsAutomationService"
            + ".isEnabled, silently treating every automation as already on for a new business")
    void seedsEveryAutomationDisabledByDefault() {
        service.create("AK PMU", "AnnaKaraPMU", "America/Los_Angeles", "annakarapmu", "s3cret!");

        ArgumentCaptor<SmsAutomation> captor = ArgumentCaptor.forClass(SmsAutomation.class);
        verify(smsAutomations, times(SmsAutomationRegistry.all().size())).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(a -> assertThat(a.getBusinessId()).isEqualTo(2L))
                .allSatisfy(a -> assertThat(a.isEnabled()).isFalse())
                .extracting(SmsAutomation::getAutomationKey)
                .containsExactlyInAnyOrderElementsOf(SmsAutomationRegistry.all().keySet());
    }

    @Test
    @DisplayName("rejects a shortCode that's already in use, saves nothing")
    void rejectsDuplicateShortCode() {
        when(businesses.findByShortCode("annakarapmu")).thenReturn(Optional.of(Business.builder().id(1L).build()));

        assertThatThrownBy(() -> service.create("AK PMU", "annakarapmu", "America/Los_Angeles", "u", "p"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("shortCode");

        verify(businesses, never()).save(any());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("rejects a username that's already in use (global uniqueness, not per-business)")
    void rejectsDuplicateUsername() {
        when(users.findByUsername("owner")).thenReturn(Optional.of(AppUser.builder().id(1L).build()));

        assertThatThrownBy(() -> service.create("AK PMU", "annakarapmu", "America/Los_Angeles", "owner", "p"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("username");

        verify(businesses, never()).save(any());
    }

    @Test
    @DisplayName("blank required fields are rejected, one at a time")
    void rejectsBlankRequiredFields() {
        assertThatThrownBy(() -> service.create("", "sc", "UTC", "u", "p"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> service.create("N", " ", "UTC", "u", "p"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("shortCode");
        assertThatThrownBy(() -> service.create("N", "sc", "", "u", "p"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("timezone");
        assertThatThrownBy(() -> service.create("N", "sc", "UTC", "", "p"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ownerUsername");
        assertThatThrownBy(() -> service.create("N", "sc", "UTC", "u", ""))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ownerPassword");
    }
}
