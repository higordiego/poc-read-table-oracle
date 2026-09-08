package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.projection.application.CalculationReadRepository.Cursor;
import com.example.projection.application.CalculationReadRepository.Filters;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

class CalculationReadRepositoryTest {

    @Test
    void cursor_roundTripsThroughEncodeAndDecode() {
        OffsetDateTime requestedAt = OffsetDateTime.parse("2026-09-04T21:17:33.066584Z");
        long calculationId = 1001L;

        String encoded = CalculationReadRepository.encodeCursor(requestedAt, calculationId);
        Cursor decoded = CalculationReadRepository.decodeCursor(encoded);

        assertThat(decoded.requestedAt()).isEqualTo(requestedAt);
        assertThat(decoded.calculationId()).isEqualTo(calculationId);
    }

    @Test
    void decodeCursor_withGarbageInput_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> CalculationReadRepository.decodeCursor("not-a-real-cursor!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cursor");
    }

    @Test
    void filters_withNoCriteria_appendsNothingToTheQuery() {
        Filters filters = new Filters(null, null, null);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM calculations WHERE 1 = 1");

        filters.appendTo(sql);

        assertThat(sql.toString()).isEqualTo("SELECT COUNT(*) FROM calculations WHERE 1 = 1");
    }

    @Test
    void filters_withAllThreeCriteria_appendsAllThreePredicatesAndBindsAllThreeParams() {
        Filters filters = new Filters(42L, "CONFIRMED", "ada@example.com");
        StringBuilder sql = new StringBuilder("SELECT * FROM calculations WHERE 1 = 1");

        filters.appendTo(sql);

        assertThat(sql.toString()).isEqualTo(
                "SELECT * FROM calculations WHERE 1 = 1"
                        + " AND customer_id = :customerId"
                        + " AND status = :status"
                        + " AND customer_email = :email");

        StatementSpec spec = mock(StatementSpec.class);
        when(spec.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(spec);

        filters.bind(spec);

        verify(spec).param("customerId", 42L);
        verify(spec).param("status", "CONFIRMED");
        verify(spec).param("email", "ada@example.com");
    }
}
