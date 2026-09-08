package com.example.projection.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

@Repository
public class CalculationReadRepository {

    private final JdbcClient jdbc;

    public CalculationReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The exact shape eligible for Memoptimized Rowstore Fast Lookup: one
     * equality predicate on the primary key, nothing else. */
    public CalculationReadResult findProjectionById(long calculationId) {
        return findById("calculation_read_projection", calculationId);
    }

    public CalculationReadResult findTransactionalById(long calculationId) {
        return findById("calculation_transactional_view", calculationId);
    }

    private CalculationReadResult findById(String source, long calculationId) {
        return jdbc.sql("SELECT * FROM " + source + " WHERE calculation_id = :id")
                .param("id", calculationId)
                .query(CalculationReadRepository::mapRow)
                .optional()
                .orElse(null);
    }

    /**
     * Production-shaped search: a {@code COUNT(*)} for the total matching
     * the filters, then a separate keyset-paginated {@code SELECT} for the
     * page itself -- two queries, not one. Keyset (seek) pagination instead
     * of {@code OFFSET}: this table has been load-tested past 2.7M rows,
     * and {@code OFFSET} has to scan and discard every skipped row to reach
     * a page, which gets steadily worse the deeper a caller pages; keyset
     * only ever reads the rows it actually returns, regardless of how deep
     * the caller has paged.
     *
     * <p>Like {@link #findProjectionById}, this intentionally does not go
     * through the memoptimized pool -- Fast Lookup only accelerates PK
     * equality lookups, not filtered/paginated collection scans.
     */
    public CalculationSearchResult search(
            String mode, Long customerId, String status, String email, int limit, String cursor) {
        String source = switch (mode.toLowerCase()) {
            case "projection" -> "calculation_read_projection";
            case "transactional" -> "calculation_transactional_view";
            default -> throw new IllegalArgumentException(
                    "mode must be 'projection' or 'transactional'");
        };

        Filters filters = new Filters(customerId, status, email);

        long total = count(source, filters);
        List<CalculationReadResult> fetched = fetchPage(source, filters, limit, cursor);

        String nextCursor = null;
        List<CalculationReadResult> page = fetched;
        if (fetched.size() > limit) {
            CalculationReadResult lastReturned = fetched.get(limit - 1);
            nextCursor = encodeCursor(lastReturned.requestedAt(), lastReturned.calculationId());
            page = fetched.subList(0, limit);
        }

        return new CalculationSearchResult(total, page, nextCursor);
    }

    private long count(String source, Filters filters) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(source).append(" WHERE 1 = 1");
        filters.appendTo(sql);
        return filters.bind(jdbc.sql(sql.toString())).query(Long.class).single();
    }

    private List<CalculationReadResult> fetchPage(String source, Filters filters, int limit, String cursor) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(source).append(" WHERE 1 = 1");
        filters.appendTo(sql);

        Cursor decoded = cursor == null ? null : decodeCursor(cursor);
        if (decoded != null) {
            sql.append(" AND (requested_at, calculation_id) < (:cursorRequestedAt, :cursorCalculationId)");
        }
        sql.append(" ORDER BY requested_at DESC, calculation_id DESC");
        sql.append(" FETCH FIRST :fetchLimit ROWS ONLY");

        // Fetch one extra row: whether it comes back tells us if there is
        // a next page, without a separate "is there more" query.
        StatementSpec spec = filters.bind(jdbc.sql(sql.toString()).param("fetchLimit", limit + 1));
        if (decoded != null) {
            spec = spec.param("cursorRequestedAt", decoded.requestedAt())
                    .param("cursorCalculationId", decoded.calculationId());
        }

        return spec.query(CalculationReadRepository::mapRow).list();
    }

    static String encodeCursor(OffsetDateTime requestedAt, long calculationId) {
        String raw = requestedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "|" + calculationId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            OffsetDateTime requestedAt = OffsetDateTime.parse(raw.substring(0, separator));
            long calculationId = Long.parseLong(raw.substring(separator + 1));
            return new Cursor(requestedAt, calculationId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid cursor: " + cursor, exception);
        }
    }

    private static CalculationReadResult mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new CalculationReadResult(
                rs.getLong("calculation_id"),
                rs.getLong("customer_id"),
                rs.getString("customer_name"),
                rs.getString("customer_email"),
                rs.getString("segment_code"),
                rs.getString("segment_name"),
                rs.getLong("price_table_id"),
                rs.getString("price_table_name"),
                rs.getString("status"),
                rs.getInt("line_item_count"),
                rs.getBigDecimal("total_amount"),
                rs.getInt("rules_applied_count"),
                rs.getBigDecimal("total_adjustment"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("calculated_at", OffsetDateTime.class));
    }

    record Filters(Long customerId, String status, String email) {
        void appendTo(StringBuilder sql) {
            if (customerId != null) {
                sql.append(" AND customer_id = :customerId");
            }
            if (status != null) {
                sql.append(" AND status = :status");
            }
            if (email != null) {
                sql.append(" AND customer_email = :email");
            }
        }

        StatementSpec bind(StatementSpec spec) {
            StatementSpec bound = spec;
            if (customerId != null) {
                bound = bound.param("customerId", customerId);
            }
            if (status != null) {
                bound = bound.param("status", status);
            }
            if (email != null) {
                bound = bound.param("email", email);
            }
            return bound;
        }
    }

    record Cursor(OffsetDateTime requestedAt, long calculationId) {
    }

    public record CalculationSearchResult(
            long total, List<CalculationReadResult> results, String nextCursor) {
    }

    public record CalculationReadResult(
            long calculationId,
            long customerId,
            String customerName,
            String customerEmail,
            String segmentCode,
            String segmentName,
            long priceTableId,
            String priceTableName,
            String status,
            int lineItemCount,
            BigDecimal totalAmount,
            int rulesAppliedCount,
            BigDecimal totalAdjustment,
            OffsetDateTime requestedAt,
            OffsetDateTime calculatedAt) {
    }
}
