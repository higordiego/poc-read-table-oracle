package com.example.projection.application;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.projection.application.CalculationCommandService.LineItemRequest;
import com.example.projection.support.NotFoundException;
import com.example.projection.support.OracleIntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalculationCommandServiceIT extends OracleIntegrationTestBase {

    @Autowired
    private CalculationCommandService calculationCommandService;

    @Test
    void create_withUnknownCustomer_throwsNotFoundException() {
        assertThrows(NotFoundException.class, () -> calculationCommandService.create(
                999_999L, 1L, List.of(new LineItemRequest(1, 1, null))));
    }

    @Test
    void create_withUnknownPriceTable_throwsNotFoundException() {
        assertThrows(NotFoundException.class, () -> calculationCommandService.create(
                1L, 999_999L, List.of(new LineItemRequest(1, 1, null))));
    }
}
