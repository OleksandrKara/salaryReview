package com.salonreview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CurrentBusinessContext} is deliberately fail-loud, not fail-silent: reading {@code id()}
 * before something has populated it is a bug (a business-scoped code path running with no request
 * filter and no {@link CurrentBusinessContext#runAs} wrapper), and should throw immediately rather
 * than return null and let a query run unscoped.
 */
class CurrentBusinessContextTest {

    @Test
    @DisplayName("reading id() before anything populates it throws — never silently returns null")
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

    @Test
    @DisplayName("clear() removes it, back to unpopulated")
    void clearRemovesIt() {
        var context = new CurrentBusinessContext();
        context.set(42L);

        context.clear();

        assertThat(context.isPopulated()).isFalse();
    }

    @Test
    @DisplayName("runAs sets the id only for the duration of the action")
    void runAsScopesToTheAction() {
        var context = new CurrentBusinessContext();
        var seenInside = new Long[1];

        context.runAs(99L, () -> seenInside[0] = context.id());

        assertThat(seenInside[0]).isEqualTo(99L);
        assertThat(context.isPopulated()).isFalse(); // restored to unpopulated after
    }

    @Test
    @DisplayName("runAs restores whatever was set before it, even if the action throws")
    void runAsRestoresPreviousValueEvenOnException() {
        var context = new CurrentBusinessContext();
        context.set(1L);

        assertThatThrownBy(() -> context.runAs(2L, () -> {
            assertThat(context.id()).isEqualTo(2L);
            throw new RuntimeException("boom");
        })).hasMessage("boom");

        assertThat(context.id()).isEqualTo(1L); // restored, not left at 2 or cleared
    }

    @Test
    @DisplayName("nested runAs calls restore the outer business, not unpopulated")
    void nestedRunAsRestoresOuterBusiness() {
        var context = new CurrentBusinessContext();
        var seenInner = new Long[1];

        context.runAs(1L, () -> context.runAs(2L, () -> seenInner[0] = context.id()));

        assertThat(seenInner[0]).isEqualTo(2L);
        assertThat(context.isPopulated()).isFalse();
    }
}
