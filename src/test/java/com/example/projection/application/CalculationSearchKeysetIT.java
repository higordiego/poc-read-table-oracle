package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.projection.application.CalculationCommandService.LineItemRequest;
import com.example.projection.application.CalculationReadRepository.CalculationSearchResult;
import com.example.projection.support.OracleIntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalculationSearchKeysetIT extends OracleIntegrationTestBase {

    @Autowired
    private CustomerService customerService;
    @Autowired
    private CalculationCommandService calculationCommandService;
    @Autowired
    private CalculationReadRepository calculationReadRepository;
    @Autowired
    private ProjectionService projectionService;

    @Test
    void consecutivePages_haveNoOverlappingRowsAndAStableTotal() {
        projectionService.ensureReady();

        long customerId = customerService.create(1, "Keyset Test Customer", "keyset-it@example.com", "ACTIVE").id();
        for (int i = 0; i < 5; i++) {
            calculationCommandService.create(customerId, 1L, List.of(new LineItemRequest(1, 1, null)));
        }

        CalculationSearchResult page1 = calculationReadRepository.search(
                "projection", customerId, null, null, 2, null);
        assertThat(page1.results()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page1.total()).isEqualTo(5);

        CalculationSearchResult page2 = calculationReadRepository.search(
                "projection", customerId, null, null, 2, page1.nextCursor());
        assertThat(page2.results()).hasSize(2);
        assertThat(page2.total()).isEqualTo(5);

        Set<Long> idsPage1 = page1.results().stream()
                .map(CalculationReadRepository.CalculationReadResult::calculationId)
                .collect(Collectors.toSet());
        Set<Long> idsPage2 = page2.results().stream()
                .map(CalculationReadRepository.CalculationReadResult::calculationId)
                .collect(Collectors.toSet());

        assertThat(idsPage1).doesNotContainAnyElementsOf(idsPage2);
    }

    @Test
    void search_withTransactionalMode_readsFromTheViewInsteadOfTheProjection() {
        projectionService.ensureReady();
        long customerId = customerService.create(1, "Transactional Mode Customer", "txn-mode-it@example.com", "ACTIVE").id();
        calculationCommandService.create(customerId, 1L, List.of(new LineItemRequest(1, 1, null)));

        CalculationSearchResult result = calculationReadRepository.search(
                "transactional", customerId, null, null, 10, null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.results()).hasSize(1);
    }

    @Test
    void search_withUnknownMode_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> calculationReadRepository.search("bogus", 1L, null, null, 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode must be");
    }
}
