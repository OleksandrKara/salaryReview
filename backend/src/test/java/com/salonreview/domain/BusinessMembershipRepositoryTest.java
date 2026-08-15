package com.salonreview.domain;

import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V85 backfills exactly one business_membership row per pre-existing app_user, pointing at Business
 * A — JpaUserDetailsService.loadUserByUsername fails loudly if any user ends up with zero or more
 * than one. This asserts the DB-level guarantee that shape relies on: UNIQUE(business_id, user_id)
 * rejects a second membership row for a user already in that business, so a re-run of a backfill-style
 * insert (or any future bug that tries to double-insert) can't silently produce a second row and
 * revive JpaUserDetailsService's "more than one" failure mode. Doesn't assert on any specific
 * pre-existing app_user — CI's Postgres starts with an empty app_user table (OwnerBootstrap only
 * seeds one when APP_OWNER_PASSWORD is set), unlike a production-data restore. Needs a real Postgres
 * to see the Flyway-applied schema (fails locally without one, passes in CI — same as
 * BusinessRepositoryTest).
 */
@SpringBootTest
class BusinessMembershipRepositoryTest {

    @Autowired
    private AppUserRepository appUsers;
    @Autowired
    private BusinessMembershipRepository memberships;
    @Autowired
    private BusinessRepository businesses;

    @Test
    void duplicateMembershipForTheSameUserAndBusinessIsRejected() {
        var businessA = businesses.findByShortCode("akluxnails").orElseThrow();
        var user = appUsers.save(AppUser.builder()
                .businessId(businessA.getId())
                .username("membership-test-" + System.nanoTime())
                .passwordHash("unused")
                .role(Role.MANAGER)
                .active(true)
                .build());

        memberships.saveAndFlush(BusinessMembership.builder()
                .businessId(businessA.getId()).userId(user.getId()).role(Role.MANAGER).build());

        assertThat(memberships.findByUserId(user.getId())).hasSize(1);

        assertThatThrownBy(() -> memberships.saveAndFlush(BusinessMembership.builder()
                        .businessId(businessA.getId()).userId(user.getId()).role(Role.MANAGER).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
