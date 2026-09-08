package com.example.projection.application;

import java.math.BigDecimal;
import java.util.List;

import com.example.projection.support.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalculationCommandService {

    private final JdbcClient jdbc;
    private final PriceResolutionService priceResolution;

    public CalculationCommandService(JdbcClient jdbc, PriceResolutionService priceResolution) {
        this.jdbc = jdbc;
        this.priceResolution = priceResolution;
    }

    @Transactional
    public CreatedCalculation create(long customerId, long priceTableId, List<LineItemRequest> items) {
        assertCustomerExists(customerId);
        assertPriceTableExists(priceTableId);

        long calculationId = jdbc.sql("SELECT calculation_seq.NEXTVAL FROM dual")
                .query(Long.class)
                .single();

        jdbc.sql("""
                INSERT INTO calculations (id, customer_id, price_table_id, status, calculated_at, total_amount)
                VALUES (:id, :customerId, :priceTableId, 'CALCULATED', SYSTIMESTAMP, 0)
                """)
                .param("id", calculationId)
                .param("customerId", customerId)
                .param("priceTableId", priceTableId)
                .update();

        BigDecimal total = BigDecimal.ZERO;
        for (LineItemRequest item : items) {
            total = total.add(insertLineItem(calculationId, item));
        }

        jdbc.sql("UPDATE calculations SET total_amount = :total WHERE id = :id")
                .param("total", total)
                .param("id", calculationId)
                .update();

        return new CreatedCalculation(calculationId, customerId, "CALCULATED", items.size(), total);
    }

    @Transactional
    public void updateStatus(long id, String status) {
        int changed = jdbc.sql("""
                UPDATE calculations
                   SET status = :status,
                       updated_at = SYSTIMESTAMP
                 WHERE id = :id
                """)
                .param("status", status)
                .param("id", id)
                .update();

        if (changed == 0) {
            throw new NotFoundException("Calculation " + id + " was not found");
        }
    }

    @Transactional
    public void delete(long id) {
        int changed = jdbc.sql("DELETE FROM calculations WHERE id = :id")
                .param("id", id)
                .update();

        if (changed == 0) {
            throw new NotFoundException("Calculation " + id + " was not found");
        }
    }

    private BigDecimal insertLineItem(long calculationId, LineItemRequest item) {
        PriceResolutionService.Resolution resolution = priceResolution.resolve(
                item.priceTableItemId(), item.quantity(), item.ruleConditionValue());

        long lineItemId = jdbc.sql("SELECT calculation_line_item_seq.NEXTVAL FROM dual")
                .query(Long.class)
                .single();

        jdbc.sql("""
                INSERT INTO calculation_line_items (
                    id, calculation_id, price_table_item_id, tariff_id, quantity,
                    unit_price, rules_applied_count, adjustment_amount, line_total)
                VALUES (
                    :id, :calculationId, :itemId, :tariffId, :quantity,
                    :unitPrice, :rulesAppliedCount, :adjustmentAmount, :lineTotal)
                """)
                .param("id", lineItemId)
                .param("calculationId", calculationId)
                .param("itemId", item.priceTableItemId())
                .param("tariffId", resolution.tariffId())
                .param("quantity", item.quantity())
                .param("unitPrice", resolution.unitPrice())
                .param("rulesAppliedCount", resolution.rulesAppliedCount())
                .param("adjustmentAmount", resolution.adjustmentAmount())
                .param("lineTotal", resolution.lineTotal())
                .update();

        return resolution.lineTotal();
    }

    private void assertCustomerExists(long customerId) {
        boolean exists = jdbc.sql("SELECT 1 FROM customers WHERE id = :id")
                .param("id", customerId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new NotFoundException("Customer " + customerId + " was not found");
        }
    }

    private void assertPriceTableExists(long priceTableId) {
        boolean exists = jdbc.sql("SELECT 1 FROM price_tables WHERE id = :id")
                .param("id", priceTableId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new NotFoundException("Price table " + priceTableId + " was not found");
        }
    }

    public record LineItemRequest(long priceTableItemId, int quantity, String ruleConditionValue) {
    }

    public record CreatedCalculation(
            long calculationId, long customerId, String status, int lineItemCount, BigDecimal totalAmount) {
    }
}
