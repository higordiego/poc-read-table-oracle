package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.projection.application.PriceResolutionService.RuleRow;
import com.example.projection.application.PriceResolutionService.TariffRow;
import com.example.projection.support.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

class PriceResolutionServiceTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final PriceResolutionService service = new PriceResolutionService(jdbc);

    @Test
    void resolve_appliesPercentTariffThenPercentRule_matchesHandCalculatedEvidence() {
        stubBasePrice(new BigDecimal("0.40"));
        stubTariff(new TariffRow(7L, "PERCENT", new BigDecimal("-20")));
        stubRules(List.of(new RuleRow("PERCENT", new BigDecimal("-3"))));

        PriceResolutionService.Resolution resolution = service.resolve(4L, 600, "PARTNER");

        assertThat(resolution.tariffId()).isEqualTo(7L);
        assertThat(resolution.unitPrice()).isEqualByComparingTo(new BigDecimal("0.3104"));
        assertThat(resolution.rulesAppliedCount()).isEqualTo(1);
        assertThat(resolution.adjustmentAmount()).isEqualByComparingTo(new BigDecimal("5.76"));
        assertThat(resolution.lineTotal()).isEqualByComparingTo(new BigDecimal("186.24"));
    }

    @Test
    void resolve_withFixedRateTariff_addsInsteadOfMultiplying() {
        stubBasePrice(new BigDecimal("10.00"));
        stubTariff(new TariffRow(1L, "FIXED", new BigDecimal("2.00")));
        stubRules(List.of());

        PriceResolutionService.Resolution resolution = service.resolve(1L, 3, null);

        assertThat(resolution.unitPrice()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(resolution.rulesAppliedCount()).isZero();
        assertThat(resolution.adjustmentAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolution.lineTotal()).isEqualByComparingTo(new BigDecimal("36.00"));
    }

    @Test
    void resolve_withNullRuleCondition_neverQueriesTariffRulesAtAll() {
        stubBasePrice(new BigDecimal("1.00"));
        stubTariff(new TariffRow(1L, "PERCENT", BigDecimal.ZERO));

        service.resolve(1L, 1, null);
    }

    @Test
    void resolve_withUnknownItem_throwsNotFoundException() {
        StatementSpec basePriceSpec = mock(StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.contains("price_table_items")))).thenReturn(basePriceSpec);
        when(basePriceSpec.param(anyString(), any())).thenReturn(basePriceSpec);
        MappedQuerySpec<BigDecimal> emptyQuery = mock(MappedQuerySpec.class);
        when(basePriceSpec.query(BigDecimal.class)).thenReturn(emptyQuery);
        when(emptyQuery.optional()).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.resolve(999L, 1, null));
    }

    @Test
    void resolve_withNoTariffMatchingTheQuantity_throwsNotFoundException() {
        stubBasePrice(new BigDecimal("1.00"));

        StatementSpec tariffSpec = mock(StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.contains("FROM tariffs")))).thenReturn(tariffSpec);
        when(tariffSpec.param(anyString(), any())).thenReturn(tariffSpec);
        MappedQuerySpec<TariffRow> emptyQuery = mock(MappedQuerySpec.class);
        when(tariffSpec.query(any(RowMapper.class))).thenReturn(emptyQuery);
        when(emptyQuery.optional()).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.resolve(1L, 100000, null));
    }

    private void stubBasePrice(BigDecimal basePrice) {
        StatementSpec spec = mock(StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.contains("price_table_items")))).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        MappedQuerySpec<BigDecimal> query = mock(MappedQuerySpec.class);
        when(spec.query(BigDecimal.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(basePrice));
    }

    private void stubTariff(TariffRow tariff) {
        StatementSpec spec = mock(StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.contains("FROM tariffs")))).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        MappedQuerySpec<TariffRow> query = mock(MappedQuerySpec.class);
        when(spec.query(any(RowMapper.class))).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(tariff));
    }

    private void stubRules(List<RuleRow> rules) {
        StatementSpec spec = mock(StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.contains("tariff_rules")))).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        MappedQuerySpec<RuleRow> query = mock(MappedQuerySpec.class);
        when(spec.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(rules);
    }
}
