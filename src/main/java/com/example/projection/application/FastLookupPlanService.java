package com.example.projection.application;

import java.util.List;

import com.example.projection.support.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FastLookupPlanService {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public FastLookupPlanService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public FastLookupPlan inspect(long calculationId) {
        FastLookupPlan result = transactions.execute(ignored -> {
            boolean found = jdbc.sql("""
                    SELECT /* fast_lookup_probe */ p.*
                      FROM calculation_read_projection p
                     WHERE calculation_id = :calculationId
                    """)
                    .param("calculationId", calculationId)
                    .query((rs, rowNumber) -> rs.getLong("calculation_id"))
                    .optional()
                    .isPresent();

            if (!found) {
                throw new NotFoundException("Calculation " + calculationId + " was not found");
            }

            List<String> lines = jdbc.sql("""
                    SELECT plan_table_output
                      FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'BASIC +PREDICATE'))
                    """)
                    .query(String.class)
                    .list();
            boolean active = lines.stream().anyMatch(line -> line.contains("READ OPTIM"));
            return new FastLookupPlan(calculationId, active, lines);
        });

        if (result == null) {
            throw new IllegalStateException("Could not inspect the Fast Lookup execution plan");
        }
        return result;
    }

    public record FastLookupPlan(long calculationId, boolean fastLookupActive, List<String> plan) {
    }
}
