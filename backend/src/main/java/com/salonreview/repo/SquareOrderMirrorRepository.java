package com.salonreview.repo;

import com.salonreview.domain.SquareOrderMirror;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface SquareOrderMirrorRepository extends JpaRepository<SquareOrderMirror, Long> {

    /** A customer's mirrored orders closed within a window — used by
     * {@code MarketingBookingPaymentMatcher} to find the order (if any) that paid for a specific
     * booking, matched by customer + a day or two around the booking's start time. */
    List<SquareOrderMirror> findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(
            Long businessId, String squareCustomerId, Instant from, Instant to);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO square_order (business_id, square_order_id, square_customer_id, state,
                closed_at, created_at, total_tip_money, total_discount_money, tenders_json,
                line_items_json, synced_at)
            VALUES (:businessId, :squareOrderId, :squareCustomerId, :state,
                :closedAt, :createdAt, :totalTipMoney, :totalDiscountMoney,
                CAST(:tendersJson AS jsonb), CAST(:lineItemsJson AS jsonb), now())
            ON CONFLICT (business_id, square_order_id) DO UPDATE SET
                square_customer_id = EXCLUDED.square_customer_id,
                state = EXCLUDED.state,
                closed_at = EXCLUDED.closed_at,
                created_at = EXCLUDED.created_at,
                total_tip_money = EXCLUDED.total_tip_money,
                total_discount_money = EXCLUDED.total_discount_money,
                tenders_json = EXCLUDED.tenders_json,
                line_items_json = EXCLUDED.line_items_json,
                synced_at = now()
            """, nativeQuery = true)
    void upsert(@Param("businessId") Long businessId, @Param("squareOrderId") String squareOrderId,
                @Param("squareCustomerId") String squareCustomerId, @Param("state") String state,
                @Param("closedAt") Instant closedAt, @Param("createdAt") Instant createdAt,
                @Param("totalTipMoney") BigDecimal totalTipMoney, @Param("totalDiscountMoney") BigDecimal totalDiscountMoney,
                @Param("tendersJson") String tendersJson, @Param("lineItemsJson") String lineItemsJson);
}
