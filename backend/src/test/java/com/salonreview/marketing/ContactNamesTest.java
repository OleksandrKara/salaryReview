package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContactNamesTest {

    @Test
    void placeholdersAreJunk() {
        for (String s : new String[] {null, "", "  ", "Test", "test test", "Tests test", "asdf", "qwerty", "-", "123", "+1 858 337 2974", "N/A", "xxx"}) {
            assertThat(ContactNames.isJunk(s)).as(String.valueOf(s)).isTrue();
        }
    }

    @Test
    void realNamesAreNotJunk() {
        for (String s : new String[] {"Oleksandr", "Anna Kara", "Testa", "Ли", "M", "Mary-Ann O'Neil"}) {
            assertThat(ContactNames.isJunk(s)).as(s).isFalse();
        }
    }

    @Test
    void sameFirstNameVariantsAreCompatibleButDifferentNamesAreNot() {
        assertThat(ContactNames.compatible("oleksandr", "Oleksandr Kara")).isTrue();
        assertThat(ContactNames.compatible("Alexandra Kim", "Alex")).isTrue();
        assertThat(ContactNames.compatible("Alexandra Kim", "Sasha")).isFalse();
        assertThat(ContactNames.compatible("Jose", "José Ruiz")).isTrue();
    }
}
