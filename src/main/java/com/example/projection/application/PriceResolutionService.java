package com.example.projection.application;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.example.projection.support.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Resolves the tariff and tariff rules applicable to a price-table item at a
 * given quantity, and computes the resulting unit price.
 *
 * <p>Contract: a {@code PERCENT} tariff or rule multiplies the running unit
 * price by {@code (1 + value / 100)}; a {@code FIXED} one adds {@code value}
 * to it. The tariff (quantity-band match) is applied first, then any
 * matching {@code tariff_rules} in priority order.
 */
@Service
public class PriceResolutionService {

    private final JdbcClient jdbc;

    public PriceResolutionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Resolution resolve(long priceTableItemId, int quantity, String ruleConditionValue) {
        BigDecimal basePrice = jdbc.sql("""
                SELECT base_price FROM price_table_items WHERE id = :itemId
                """)
                .param("itemId", priceTableItemId)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> new NotFoundException(
                        "Price table item " + priceTableItemId + " was not found"));

        TariffRow tariff = jdbc.sql("""
                SELECT id, rate_type, rate_value
                  FROM tariffs
                 WHERE price_table_item_id = :itemId
                   AND :quantity >= min_quantity
                   AND (max_quantity IS NULL OR :quantity <= max_quantity)
                   AND valid_from <= SYSDATE
                   AND (valid_to IS NULL OR valid_to >= SYSDATE)
                 ORDER BY min_quantity DESC
                 FETCH FIRST 1 ROW ONLY
                """)
                .param("itemId", priceTableItemId)
                .param("quantity", quantity)
                .query(PriceResolutionService::mapTariff)
                .optional()
                .orElseThrow(() -> new NotFoundException(
                        "No tariff applies to item " + priceTableItemId + " at quantity " + quantity));

        BigDecimal unitPriceAfterTariff = apply(basePrice, tariff.rateType(), tariff.rateValue());

        List<RuleRow> rules = ruleConditionValue == null
                ? List.of()
                : jdbc.sql("""
                        SELECT adjustment_type, adjustment_value
                          FROM tariff_rules
                         WHERE tariff_id = :tariffId
                           AND condition_value = :conditionValue
                         ORDER BY priority
                        """)
                        .param("tariffId", tariff.id())
                        .param("conditionValue", ruleConditionValue)
                        .query(PriceResolutionService::mapRule)
                        .list();

        BigDecimal unitPrice = unitPriceAfterTariff;
        for (RuleRow rule : rules) {
            unitPrice = apply(unitPrice, rule.adjustmentType(), rule.adjustmentValue());
        }

        BigDecimal adjustmentPerUnit = unitPriceAfterTariff.subtract(unitPrice);
        BigDecimal quantityAmount = BigDecimal.valueOf(quantity);
        BigDecimal adjustmentAmount = adjustmentPerUnit.multiply(quantityAmount);
        BigDecimal lineTotal = unitPrice.multiply(quantityAmount);

        return new Resolution(tariff.id(), unitPrice, rules.size(), adjustmentAmount, lineTotal);
    }

    private static BigDecimal apply(BigDecimal amount, String adjustmentType, BigDecimal value) {
        return "FIXED".equals(adjustmentType)
                ? amount.add(value)
                : amount.multiply(BigDecimal.ONE.add(value.movePointLeft(2)));
    }

    static TariffRow mapTariff(ResultSet rs, int rowNumber) throws SQLException {
        return new TariffRow(rs.getLong("id"), rs.getString("rate_type"), rs.getBigDecimal("rate_value"));
    }

    static RuleRow mapRule(ResultSet rs, int rowNumber) throws SQLException {
        return new RuleRow(rs.getString("adjustment_type"), rs.getBigDecimal("adjustment_value"));
    }

    record TariffRow(long id, String rateType, BigDecimal rateValue) {
    }

    record RuleRow(String adjustmentType, BigDecimal adjustmentValue) {
    }

    public record Resolution(
            long tariffId,
            BigDecimal unitPrice,
            int rulesAppliedCount,
            BigDecimal adjustmentAmount,
            BigDecimal lineTotal) {
    }
}
