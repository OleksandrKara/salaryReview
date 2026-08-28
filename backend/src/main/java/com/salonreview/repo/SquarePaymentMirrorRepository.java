package com.salonreview.repo;

import com.salonreview.domain.SquarePaymentMirror;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface SquarePaymentMirrorRepository extends JpaRepository<SquarePaymentMirror, Long> {

    List<SquarePaymentMirror> findByBusinessIdAndSquareOrderId(Long businessId, String squareOrderId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO square_payment (business_id, square_payment_id, square_order_id,
                square_customer_id, status, created_at, total_money, tip_money, synced_at)
            VALUES (:businessId, :squarePaymentId, :squareOrderId, :squareCustomerId, :status,
                :createdAt, :totalMoney, :tipMoney, now())
            ON CONFLICT (business_id, square_payment_id) DO UPDATE SET
                square_order_id = EXCLUDED.square_order_id,
                square_customer_id = EXCLUDED.square_customer_id,
                status = EXCLUDED.status,
                created_at = EXCLUDED.created_at,
                total_money = EXCLUDED.total_money,
                tip_money = EXCLUDED.tip_money,
                synced_at = now()
            """, nativeQuery = true)
    void upsert(@Param("businessId") Long businessId, @Param("squarePaymentId") String squarePaymentId,
                @Param("squareOrderId") String squareOrderId, @Param("squareCustomerId") String squareCustomerId,
                @Param("status") String status, @Param("createdAt") Instant createdAt,
                @Param("totalMoney") BigDecimal totalMoney, @Param("tipMoney") BigDecimal tipMoney);
}
