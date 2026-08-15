package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real production incident: sessions are DB-backed (spring_session_jdbc) and
 * persist across deploys, so {@link AppUserPrincipal} (which implements {@code Serializable} via
 * {@code UserDetails}) needs a pinned {@code serialVersionUID} — without one, adding a field bumps
 * Java's auto-computed UID, and every session created before that deploy throws
 * {@code InvalidClassException} on its very next request (a bare 500, not a clean 401, since it
 * happens deep in session restoration before the controller ever runs). This happened for real when
 * {@code activeBusinessId} was added with no pinned UID, silently breaking every already-logged-in
 * session until the stale rows were manually cleared from the database.
 */
class AppUserPrincipalSerializationTest {

    @Test
    void declaresAPinnedSerialVersionUid() {
        // ObjectStreamClass.lookup() reads the *declared* serialVersionUID field if present, or
        // computes the default one otherwise — comparing this against a hardcoded expectation catches
        // both "no field at all" and "someone changed the pinned value without meaning to".
        long uid = ObjectStreamClass.lookup(AppUserPrincipal.class).getSerialVersionUID();
        assertThat(uid).isEqualTo(1L);
    }

    @Test
    void survivesAJavaSerializationRoundTrip() throws Exception {
        AppUser user = AppUser.builder().id(7L).businessId(1L).username("susan")
                .passwordHash("hashed").role(Role.PROVIDER).providerId(3L).active(true).build();
        AppUserPrincipal original = new AppUserPrincipal(user, 1L);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        AppUserPrincipal restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (AppUserPrincipal) in.readObject();
        }

        assertThat(restored.getUserId()).isEqualTo(7L);
        assertThat(restored.getUsername()).isEqualTo("susan");
        assertThat(restored.getRole()).isEqualTo(Role.PROVIDER);
        assertThat(restored.getProviderId()).isEqualTo(3L);
        assertThat(restored.getActiveBusinessId()).isEqualTo(1L);
        assertThat(restored.isEnabled()).isTrue();
    }
}
