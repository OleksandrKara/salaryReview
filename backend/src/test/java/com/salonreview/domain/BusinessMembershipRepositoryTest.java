package com.salonreview.domain;

import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V85 backfills exactly one business_membership row per existing app_user, pointing at Business A —
 * JpaUserDetailsService.loadUserByUsername fails loudly if any user ends up with zero or more than
 * one, so this asserts the backfill actually produced that 1:1 shape for every seeded account. Needs
 * a real Postgres to see the Flyway-applied migration (fails locally without one, passes in CI — same
 * as BusinessRepositoryTest).
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
    void everyExistingAppUserHasExactlyOneMembershipRowInBusinessA() {
        var businessA = businesses.findByShortCode("akluxnails").orElseThrow();

        var allUsers = appUsers.findAll();
        assertThat(allUsers).isNotEmpty();

        for (AppUser user : allUsers) {
            var rows = memberships.findByUserId(user.getId());
            assertThat(rows)
                    .as("user '%s' should have exactly one business_membership row", user.getUsername())
                    .hasSize(1);
            assertThat(rows.get(0).getBusinessId()).isEqualTo(businessA.getId());
        }
    }
}
