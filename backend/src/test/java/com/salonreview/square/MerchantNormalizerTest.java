package com.salonreview.square;

import com.salonreview.domain.MerchantAlias;
import com.salonreview.repo.MerchantAliasRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Table-driven tests for {@link MerchantNormalizer} (openspec design.md §15, D2). */
class MerchantNormalizerTest {

    @Test
    @DisplayName("The exact prompt example set all reduce to the same normalized merchant")
    void squareDescriptorVariantsCollapseToSameMerchant() {
        String a = MerchantNormalizer.stripNoise("SQ *AKLUXNAILS");
        String b = MerchantNormalizer.stripNoise("SQ AKLUXNAILS");
        String c = MerchantNormalizer.stripNoise("SQ* AKLUX NAILS");
        String d = MerchantNormalizer.stripNoise("Square AKLUXNAILS");

        assertThat(a).isEqualTo("AKLUXNAILS");
        assertThat(b).isEqualTo("AKLUXNAILS");
        assertThat(c).isEqualTo("AKLUXNAILS");
        assertThat(d).isEqualTo("AKLUXNAILS");
    }

    @ParameterizedTest
    @CsvSource({
            "POS DEBIT WALMART, WALMART",
            "POS PURCHASE TARGET, TARGET",
            "CHECKCARD 1234 COSTCO WHSE, COSTCOWHSE",
    })
    @DisplayName("Prefix noise (POS DEBIT/PURCHASE, CHECKCARD) is stripped")
    void prefixNoiseIsStripped(String raw, String expected) {
        assertThat(MerchantNormalizer.stripNoise(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("A trailing reference number is stripped")
    void trailingReferenceNumberIsStripped() {
        assertThat(MerchantNormalizer.stripNoise("WM SUPERCENTER #4821")).isEqualTo("WMSUPERCENTER");
    }

    @Test
    @DisplayName("A trailing city/state suffix is stripped")
    void trailingCityStateIsStripped() {
        assertThat(MerchantNormalizer.stripNoise("SHELL OIL LOS ANGELES CA")).isEqualTo("SHELLOIL");
    }

    @Test
    @DisplayName("toMerchantKey removes whitespace from a free-form canonical name")
    void merchantKeyStripsWhitespace() {
        assertThat(MerchantNormalizer.toMerchantKey("Home Depot")).isEqualTo("HomeDepot");
    }

    @Test
    @DisplayName("A confirmed merchant alias is applied on every future import")
    void aliasIsApplied() {
        MerchantAliasRepository aliases = mock(MerchantAliasRepository.class);
        when(aliases.findByRawPattern("AKLUXNAILSOLD"))
                .thenReturn(Optional.of(MerchantAlias.builder().rawPattern("AKLUXNAILSOLD").canonicalMerchant("AKLUXNAILS").build()));
        MerchantNormalizer normalizer = new MerchantNormalizer(aliases);

        MerchantNormalizer.Normalized result = normalizer.normalize("SQ *AKLUXNAILSOLD");

        assertThat(result.normalizedMerchant()).isEqualTo("AKLUXNAILS");
    }

    @Test
    @DisplayName("No alias means the stripped descriptor is used as-is")
    void noAliasUsesStrippedDescriptor() {
        MerchantAliasRepository aliases = mock(MerchantAliasRepository.class);
        when(aliases.findByRawPattern("COSTCO")).thenReturn(Optional.empty());
        MerchantNormalizer normalizer = new MerchantNormalizer(aliases);

        MerchantNormalizer.Normalized result = normalizer.normalize("COSTCO WHSE #123");

        assertThat(result.normalizedMerchant()).isEqualTo("COSTCOWHSE");
        assertThat(result.merchantKey()).isEqualTo("COSTCOWHSE");
    }

    @Test
    @DisplayName("A bare check number is reference-number-only")
    void bareCheckNumberIsReferenceOnly() {
        assertThat(MerchantNormalizer.isReferenceNumberOnly("CHECK #1042")).isTrue();
        assertThat(MerchantNormalizer.isReferenceNumberOnly("CHECK 1042")).isTrue();
    }

    @Test
    @DisplayName("A real merchant descriptor is not reference-number-only")
    void realMerchantIsNotReferenceOnly() {
        assertThat(MerchantNormalizer.isReferenceNumberOnly("COSTCO WHSE #123")).isFalse();
    }
}
