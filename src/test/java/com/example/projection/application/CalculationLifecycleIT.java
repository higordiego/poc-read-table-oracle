package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.projection.application.CalculationCommandService.LineItemRequest;
import com.example.projection.application.CalculationReadRepository.CalculationReadResult;
import com.example.projection.support.NotFoundException;
import com.example.projection.support.OracleIntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalculationLifecycleIT extends OracleIntegrationTestBase {

    @Autowired
    private CustomerService customerService;
    @Autowired
    private CalculationCommandService calculationCommandService;
    @Autowired
    private CalculationReadRepository calculationReadRepository;
    @Autowired
    private ProjectionService projectionService;

    @Test
    void createCalculation_isImmediatelyVisibleInTheProjection_withCorrectTotals() {
        projectionService.ensureReady();

        long customerId = customerService.create(2, "IT Test Customer", "it-test@example.com", "ACTIVE").id();

        CalculationCommandService.CreatedCalculation created = calculationCommandService.create(
                customerId,
                2L,
                List.of(
                        new LineItemRequest(4, 600, "PARTNER"),
                        new LineItemRequest(5, 2000, null)));

        assertThat(created.totalAmount()).isEqualByComparingTo(new BigDecimal("330.24"));

        CalculationReadResult projected = calculationReadRepository.findProjectionById(created.calculationId());
        assertThat(projected).isNotNull();
        assertThat(projected.totalAmount()).isEqualByComparingTo(new BigDecimal("330.24"));
        assertThat(projected.lineItemCount()).isEqualTo(2);
        assertThat(projected.rulesAppliedCount()).isEqualTo(1);
        assertThat(projected.totalAdjustment()).isEqualByComparingTo(new BigDecimal("5.76"));
        assertThat(projected.customerId()).isEqualTo(customerId);
    }

    @Test
    void updateStatus_propagatesToProjectionInTheSameTransaction() {
        projectionService.ensureReady();

        long customerId = customerService.create(1, "Status Test Customer", "status-it@example.com", "ACTIVE").id();
        long calculationId = calculationCommandService.create(
                customerId, 1L, List.of(new LineItemRequest(1, 10, null))).calculationId();

        calculationCommandService.updateStatus(calculationId, "CONFIRMED");

        CalculationReadResult projected = calculationReadRepository.findProjectionById(calculationId);
        assertThat(projected.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void delete_removesTheCalculationFromTheProjectionToo() {
        projectionService.ensureReady();

        long customerId = customerService.create(1, "Delete Test Customer", "delete-it@example.com", "ACTIVE").id();
        long calculationId = calculationCommandService.create(
                customerId, 1L, List.of(new LineItemRequest(1, 10, null))).calculationId();
        assertThat(calculationReadRepository.findProjectionById(calculationId)).isNotNull();

        calculationCommandService.delete(calculationId);

        assertThat(calculationReadRepository.findProjectionById(calculationId)).isNull();
        assertThrows(NotFoundException.class, () -> calculationCommandService.updateStatus(calculationId, "CONFIRMED"));
    }
}
