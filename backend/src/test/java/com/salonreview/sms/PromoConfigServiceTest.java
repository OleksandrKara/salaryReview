package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.BusinessPromoConfig;
import com.salonreview.repo.BusinessPromoConfigRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PromoConfigServiceTest {

    private static final Long BUSINESS_A_ID = 1L;
    private static final Long OTHER_BUSINESS_ID = 2L;

    private BusinessPromoConfigRepository repository;
    private BusinessRepository businesses;
    private SquareClientProvider squareClientProvider;
    private RebookingProperties legacyProperties;
    private SquareClient square;
    private PromoConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(BusinessPromoConfigRepository.class);
        businesses = mock(BusinessRepository.class);
        squareClientProvider = mock(SquareClientProvider.class);
        legacyProperties = new RebookingProperties();
        square = mock(SquareClient.class);
        when(businesses.legacySmsBusiness()).thenReturn(Business.builder().id(BUSINESS_A_ID).shortCode("akluxnails").build());
        when(squareClientProvider.forBusiness(OTHER_BUSINESS_ID)).thenReturn(square);
        service = new PromoConfigService(repository, businesses, squareClientProvider, legacyProperties);
    }

    @Test
    @DisplayName("Business A with legacy env config set → get() returns the known $10/no-min and $5/$99-min terms")
    void businessALegacyFallback() {
        legacyProperties.setAutoDiscountGroupId("GROUPA");
        legacyProperties.setWinbackAutoDiscountGroupId("GROUPB");
        when(repository.findByBusinessIdAndPromoCode(BUSINESS_A_ID, "REBOOK10")).thenReturn(Optional.empty());
        when(repository.findByBusinessIdAndPromoCode(BUSINESS_A_ID, "WINBACK5")).thenReturn(Optional.empty());

        var rebook = service.get(BUSINESS_A_ID, "REBOOK10").orElseThrow();
        assertThat(rebook.discountCents()).isEqualTo(1000);
        assertThat(rebook.minSpendCents()).isNull();
        assertThat(rebook.squareCustomerGroupId()).isEqualTo("GROUPA");
        assertThat(rebook.configured()).isTrue();

        var winback = service.get(BUSINESS_A_ID, "WINBACK5").orElseThrow();
        assertThat(winback.discountCents()).isEqualTo(500);
        assertThat(winback.minSpendCents()).isEqualTo(9900);
        assertThat(winback.squareCustomerGroupId()).isEqualTo("GROUPB");
    }

    @Test
    @DisplayName("Business A with no env config set → get() is empty, not a default")
    void businessANoLegacyConfigIsEmpty() {
        when(repository.findByBusinessIdAndPromoCode(any(), any())).thenReturn(Optional.empty());

        assertThat(service.get(BUSINESS_A_ID, "REBOOK10")).isEmpty();
    }

    @Test
    @DisplayName("another business with no row and no legacy fallback → get() is empty")
    void otherBusinessNoRowIsEmpty() {
        when(repository.findByBusinessIdAndPromoCode(OTHER_BUSINESS_ID, "REBOOK10")).thenReturn(Optional.empty());

        assertThat(service.get(OTHER_BUSINESS_ID, "REBOOK10")).isEmpty();
    }

    @Test
    @DisplayName("another business with a DB row → get() returns it, unconfigured until Square objects exist")
    void otherBusinessWithRow() {
        BusinessPromoConfig row = BusinessPromoConfig.builder().businessId(OTHER_BUSINESS_ID).promoCode("REBOOK10")
                .discountCents(1500).minSpendCents(30000).build();
        when(repository.findByBusinessIdAndPromoCode(OTHER_BUSINESS_ID, "REBOOK10")).thenReturn(Optional.of(row));

        var terms = service.get(OTHER_BUSINESS_ID, "REBOOK10").orElseThrow();
        assertThat(terms.discountCents()).isEqualTo(1500);
        assertThat(terms.minSpendCents()).isEqualTo(30000);
        assertThat(terms.configured()).isFalse();
    }

    @Test
    @DisplayName("save() for Business A is refused — its terms are managed outside this tool")
    void saveRefusedForBusinessA() {
        assertThatThrownBy(() -> service.save(BUSINESS_A_ID, "REBOOK10", 1000, null, "owner"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(squareClientProvider);
    }

    @Test
    @DisplayName("first save for a business/promoCode creates the product set, customer group, and discount+pricing rule")
    void firstSaveCreatesSquareObjects() {
        when(repository.findByBusinessIdAndPromoCode(OTHER_BUSINESS_ID, "REBOOK10")).thenReturn(Optional.empty());
        when(repository.findAllByBusinessId(OTHER_BUSINESS_ID)).thenReturn(List.of());
        when(square.createAllProductsSet(any())).thenReturn("PRODSET1");
        when(square.createCustomerGroup(any())).thenReturn("GROUP1");
        when(square.createDiscountAndPricingRule(any(), eq(1500L), eq("GROUP1"), eq(30000L), eq("PRODSET1")))
                .thenReturn(new SquareClient.PromoCatalogIds("DISC1", "RULE1"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var terms = service.save(OTHER_BUSINESS_ID, "REBOOK10", 1500, 30000L, "owner");

        assertThat(terms.discountCents()).isEqualTo(1500);
        assertThat(terms.minSpendCents()).isEqualTo(30000);
        assertThat(terms.squareCustomerGroupId()).isEqualTo("GROUP1");
        assertThat(terms.configured()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(BusinessPromoConfig.class);
        verify(repository).save(captor.capture());
        BusinessPromoConfig saved = captor.getValue();
        assertThat(saved.getSquareDiscountCatalogId()).isEqualTo("DISC1");
        assertThat(saved.getSquarePricingRuleCatalogId()).isEqualTo("RULE1");
        assertThat(saved.getSquareProductSetCatalogId()).isEqualTo("PRODSET1");
        assertThat(saved.getUpdatedBy()).isEqualTo("owner");
    }

    @Test
    @DisplayName("second promo code for the same business reuses the already-created product set")
    void secondPromoReusesProductSet() {
        BusinessPromoConfig existingRebook = BusinessPromoConfig.builder().businessId(OTHER_BUSINESS_ID).promoCode("REBOOK10")
                .squareProductSetCatalogId("PRODSET1").build();
        when(repository.findByBusinessIdAndPromoCode(OTHER_BUSINESS_ID, "WINBACK5")).thenReturn(Optional.empty());
        when(repository.findAllByBusinessId(OTHER_BUSINESS_ID)).thenReturn(List.of(existingRebook));
        when(square.createCustomerGroup(any())).thenReturn("GROUP2");
        when(square.createDiscountAndPricingRule(any(), eq(500L), eq("GROUP2"), eq(9900L), eq("PRODSET1")))
                .thenReturn(new SquareClient.PromoCatalogIds("DISC2", "RULE2"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save(OTHER_BUSINESS_ID, "WINBACK5", 500, 9900L, "owner");

        verify(square, never()).createAllProductsSet(any());
    }

    @Test
    @DisplayName("saving an existing business/promoCode updates the Square objects in place instead of creating new ones")
    void resaveUpdatesExistingSquareObjects() {
        BusinessPromoConfig existing = BusinessPromoConfig.builder().businessId(OTHER_BUSINESS_ID).promoCode("REBOOK10")
                .discountCents(1000).minSpendCents(null).squareCustomerGroupId("GROUP1")
                .squareDiscountCatalogId("DISC1").squarePricingRuleCatalogId("RULE1").squareProductSetCatalogId("PRODSET1")
                .build();
        when(repository.findByBusinessIdAndPromoCode(OTHER_BUSINESS_ID, "REBOOK10")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var terms = service.save(OTHER_BUSINESS_ID, "REBOOK10", 2000, 5000L, "owner");

        assertThat(terms.discountCents()).isEqualTo(2000);
        assertThat(terms.minSpendCents()).isEqualTo(5000);
        verify(square).updatePromoTerms("DISC1", "RULE1", 2000L, 5000L);
        verify(square, never()).createCustomerGroup(any());
        verify(square, never()).createDiscountAndPricingRule(any(), anyLong(), any(), any(), any());
    }
}
