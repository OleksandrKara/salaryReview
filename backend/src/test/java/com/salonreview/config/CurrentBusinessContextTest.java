package com.salonreview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CurrentBusinessContext} is deliberately fail-loud, not fail-silent: reading {@code id()}
 * before the request-populating filter has run is a bug (a business-scoped code path running outside
 * authentication), and should throw immediately rather than return null and let a query run unscoped.
 */
class CurrentBusinessContextTest {

    @Test
    @DisplayName("reading id() before the filter populates it throws — never silently returns null")
    void throwsBeforePopulated() {
        var context = new CurrentBusinessContext();

        assertThat(context.isPopulated()).isFalse();
        assertThatThrownBy(context::id).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("after the filter sets it, id() returns exactly that business")
    void returnsSetBusinessId() {
        var context = new CurrentBusinessContext();

        context.set(42L);

        assertThat(context.isPopulated()).isTrue();
        assertThat(context.id()).isEqualTo(42L);
    }
}
