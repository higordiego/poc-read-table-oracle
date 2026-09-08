package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.projection.application.CalculationCommandService.LineItemRequest;
import com.example.projection.application.FastLookupPlanService.FastLookupPlan;
import com.example.projection.support.NotFoundException;
import com.example.projection.support.OracleIntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FastLookupPlanServiceIT extends OracleIntegrationTestBase {

    @Autowired
    private CustomerService customerService;
    @Autowired
    private CalculationCommandService calculationCommandService;
    @Autowired
    private FastLookupPlanService planService;
    @Autowired
    private ProjectionService projectionService;

    @Test
    void inspect_returnsANonEmptyPlanForAnExistingCalculation() {
        projectionService.ensureReady();
        long customerId = customerService.create(1, "Plan Test Customer", "plan-it@example.com", "ACTIVE").id();
        long calculationId = calculationCommandService.create(
                customerId, 1L, List.of(new LineItemRequest(1, 5, null))).calculationId();

        FastLookupPlan plan = planService.inspect(calculationId);

        assertThat(plan.calculationId()).isEqualTo(calculationId);
        assertThat(plan.plan()).isNotEmpty();
    }

    @Test
    void inspect_whenCalculationDoesNotExist_throwsNotFoundException() {
        projectionService.ensureReady();

        assertThrows(NotFoundException.class, () -> planService.inspect(999_999));
    }
}
