-- Source of Truth: a customer price-table calculation domain.
--
-- customer_segments -> price_tables -> price_table_items -> tariffs ->
-- tariff_rules is reference/catalog data: few rows, written rarely. The
-- high-volume transactional tables are calculations and
-- calculation_line_items -- each line item resolves through a real 5-level
-- join chain (item -> price_table -> segment, tariff -> rules), unlike a
-- flat set of 1:1 context tables.

CREATE SEQUENCE customer_seq START WITH 1000 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE calculation_seq START WITH 1000 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE calculation_line_item_seq START WITH 1000 INCREMENT BY 1 NOCACHE;

CREATE TABLE customer_segments (
    id      NUMBER(19)    NOT NULL,
    code    VARCHAR2(30)  NOT NULL,
    name    VARCHAR2(100) NOT NULL,
    CONSTRAINT pk_customer_segments PRIMARY KEY (id),
    CONSTRAINT uq_customer_segments_code UNIQUE (code)
);

CREATE TABLE customers (
    id         NUMBER(19)    NOT NULL,
    segment_id NUMBER(19)    NOT NULL,
    name       VARCHAR2(200) NOT NULL,
    email      VARCHAR2(320) NOT NULL,
    status     VARCHAR2(20)  NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT fk_customers_segment FOREIGN KEY (segment_id)
        REFERENCES customer_segments (id),
    CONSTRAINT ck_customers_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX idx_customers_segment ON customers (segment_id);

CREATE TABLE price_tables (
    id         NUMBER(19)    NOT NULL,
    segment_id NUMBER(19)    NOT NULL,
    name       VARCHAR2(150) NOT NULL,
    currency   VARCHAR2(3)   DEFAULT 'BRL' NOT NULL,
    valid_from DATE          NOT NULL,
    valid_to   DATE,
    status     VARCHAR2(20)  DEFAULT 'ACTIVE' NOT NULL,
    CONSTRAINT pk_price_tables PRIMARY KEY (id),
    CONSTRAINT fk_price_tables_segment FOREIGN KEY (segment_id)
        REFERENCES customer_segments (id),
    CONSTRAINT ck_price_tables_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX idx_price_tables_segment ON price_tables (segment_id);

CREATE TABLE price_table_items (
    id             NUMBER(19)    NOT NULL,
    price_table_id NUMBER(19)    NOT NULL,
    product_code   VARCHAR2(40)  NOT NULL,
    description    VARCHAR2(200) NOT NULL,
    base_price     NUMBER(19,4)  NOT NULL,
    CONSTRAINT pk_price_table_items PRIMARY KEY (id),
    CONSTRAINT fk_price_table_items_table FOREIGN KEY (price_table_id)
        REFERENCES price_tables (id)
);
CREATE INDEX idx_price_table_items_table ON price_table_items (price_table_id);

CREATE TABLE tariffs (
    id                  NUMBER(19)   NOT NULL,
    price_table_item_id NUMBER(19)   NOT NULL,
    min_quantity        NUMBER(10)   DEFAULT 1 NOT NULL,
    max_quantity        NUMBER(10),
    rate_type           VARCHAR2(10) NOT NULL,
    rate_value          NUMBER(19,4) NOT NULL,
    valid_from          DATE         NOT NULL,
    valid_to            DATE,
    CONSTRAINT pk_tariffs PRIMARY KEY (id),
    CONSTRAINT fk_tariffs_item FOREIGN KEY (price_table_item_id)
        REFERENCES price_table_items (id),
    CONSTRAINT ck_tariffs_rate_type CHECK (rate_type IN ('PERCENT', 'FIXED'))
);
CREATE INDEX idx_tariffs_item_qty ON tariffs (price_table_item_id, min_quantity);

CREATE TABLE tariff_rules (
    id               NUMBER(19)    NOT NULL,
    tariff_id        NUMBER(19)    NOT NULL,
    rule_type        VARCHAR2(20)  NOT NULL,
    condition_value  VARCHAR2(100) NOT NULL,
    adjustment_type  VARCHAR2(10)  NOT NULL,
    adjustment_value NUMBER(19,4)  NOT NULL,
    priority         NUMBER(5)     DEFAULT 0 NOT NULL,
    CONSTRAINT pk_tariff_rules PRIMARY KEY (id),
    CONSTRAINT fk_tariff_rules_tariff FOREIGN KEY (tariff_id)
        REFERENCES tariffs (id),
    CONSTRAINT ck_tariff_rules_adj_type CHECK (adjustment_type IN ('PERCENT', 'FIXED'))
);
CREATE INDEX idx_tariff_rules_tariff ON tariff_rules (tariff_id, priority);

CREATE TABLE calculations (
    id             NUMBER(19)   NOT NULL,
    customer_id    NUMBER(19)   NOT NULL,
    price_table_id NUMBER(19)   NOT NULL,
    status         VARCHAR2(20) DEFAULT 'CALCULATED' NOT NULL,
    requested_at   TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    calculated_at  TIMESTAMP(6) WITH TIME ZONE,
    total_amount   NUMBER(19,4) DEFAULT 0 NOT NULL,
    updated_at     TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_calculations PRIMARY KEY (id),
    CONSTRAINT fk_calculations_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id),
    CONSTRAINT fk_calculations_price_table FOREIGN KEY (price_table_id)
        REFERENCES price_tables (id),
    CONSTRAINT ck_calculations_status
        CHECK (status IN ('DRAFT', 'CALCULATED', 'CONFIRMED', 'CANCELLED'))
);
CREATE INDEX idx_calculations_customer_requested
    ON calculations (customer_id, requested_at DESC);
CREATE INDEX idx_calculations_status_requested
    ON calculations (status, requested_at DESC);

CREATE TABLE calculation_line_items (
    id                  NUMBER(19)   NOT NULL,
    calculation_id      NUMBER(19)   NOT NULL,
    price_table_item_id NUMBER(19)   NOT NULL,
    tariff_id           NUMBER(19),
    quantity             NUMBER(10)   NOT NULL,
    unit_price            NUMBER(19,4) NOT NULL,
    rules_applied_count    NUMBER(5)    DEFAULT 0 NOT NULL,
    adjustment_amount       NUMBER(19,4) DEFAULT 0 NOT NULL,
    line_total                NUMBER(19,4) NOT NULL,
    CONSTRAINT pk_calculation_line_items PRIMARY KEY (id),
    CONSTRAINT fk_calc_line_items_calc FOREIGN KEY (calculation_id)
        REFERENCES calculations (id) ON DELETE CASCADE,
    CONSTRAINT fk_calc_line_items_item FOREIGN KEY (price_table_item_id)
        REFERENCES price_table_items (id),
    CONSTRAINT fk_calc_line_items_tariff FOREIGN KEY (tariff_id)
        REFERENCES tariffs (id)
);
CREATE INDEX idx_calc_line_items_calc ON calculation_line_items (calculation_id);

-- The projection lives in two physical slots behind a synonym from the
-- start (no "rename an existing table" step needed, unlike the earlier
-- iteration of this PoC). Both slots enable MEMOPTIMIZE FOR READ in the
-- CREATE TABLE itself: ORA-62149 refuses to enable it later via ALTER TABLE
-- once an index on a TIMESTAMP WITH TIME ZONE column (requested_at, below)
-- has added its hidden virtual column, so it has to be on before any index
-- exists.
CREATE TABLE calculation_read_projection_a (
    calculation_id         NUMBER(19)    NOT NULL,
    customer_id            NUMBER(19)    NOT NULL,
    customer_name          VARCHAR2(200) NOT NULL,
    customer_email         VARCHAR2(320) NOT NULL,
    segment_code           VARCHAR2(30)  NOT NULL,
    segment_name           VARCHAR2(100) NOT NULL,
    price_table_id         NUMBER(19)    NOT NULL,
    price_table_name       VARCHAR2(150) NOT NULL,
    status                 VARCHAR2(20)  NOT NULL,
    line_item_count        NUMBER(10)    NOT NULL,
    total_amount           NUMBER(19,4)  NOT NULL,
    rules_applied_count    NUMBER(10)    DEFAULT 0 NOT NULL,
    total_adjustment       NUMBER(19,4)  DEFAULT 0 NOT NULL,
    requested_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    calculated_at          TIMESTAMP(6) WITH TIME ZONE,
    projection_updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_calculation_read_projection_a PRIMARY KEY (calculation_id)
)
SEGMENT CREATION IMMEDIATE
MEMOPTIMIZE FOR READ;

CREATE INDEX idx_projection_a_customer_requested
    ON calculation_read_projection_a (customer_id, requested_at DESC);
CREATE INDEX idx_projection_a_status_requested
    ON calculation_read_projection_a (status, requested_at DESC);
CREATE INDEX idx_projection_a_email
    ON calculation_read_projection_a (customer_email);

CREATE TABLE calculation_read_projection_b (
    calculation_id         NUMBER(19)    NOT NULL,
    customer_id            NUMBER(19)    NOT NULL,
    customer_name          VARCHAR2(200) NOT NULL,
    customer_email         VARCHAR2(320) NOT NULL,
    segment_code           VARCHAR2(30)  NOT NULL,
    segment_name           VARCHAR2(100) NOT NULL,
    price_table_id         NUMBER(19)    NOT NULL,
    price_table_name       VARCHAR2(150) NOT NULL,
    status                 VARCHAR2(20)  NOT NULL,
    line_item_count        NUMBER(10)    NOT NULL,
    total_amount           NUMBER(19,4)  NOT NULL,
    rules_applied_count    NUMBER(10)    DEFAULT 0 NOT NULL,
    total_adjustment       NUMBER(19,4)  DEFAULT 0 NOT NULL,
    requested_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    calculated_at          TIMESTAMP(6) WITH TIME ZONE,
    projection_updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_calculation_read_projection_b PRIMARY KEY (calculation_id)
)
SEGMENT CREATION IMMEDIATE
MEMOPTIMIZE FOR READ;

CREATE INDEX idx_projection_b_customer_requested
    ON calculation_read_projection_b (customer_id, requested_at DESC);
CREATE INDEX idx_projection_b_status_requested
    ON calculation_read_projection_b (status, requested_at DESC);
CREATE INDEX idx_projection_b_email
    ON calculation_read_projection_b (customer_email);

-- The active pointer. Triggers, projection_maintenance_pkg and the Java
-- app all reference "calculation_read_projection" unmodified; Oracle
-- resolves the synonym transparently, including for DBMS_XPLAN.
CREATE SYNONYM calculation_read_projection FOR calculation_read_projection_a;

CREATE TABLE projection_control (
    projection_name          VARCHAR2(100) NOT NULL,
    status                    VARCHAR2(30)  NOT NULL,
    schema_version            NUMBER(10)    NOT NULL,
    active_slot               CHAR(1)       DEFAULT 'A' NOT NULL,
    last_built_at              TIMESTAMP(6) WITH TIME ZONE,
    last_validated_at           TIMESTAMP(6) WITH TIME ZONE,
    population_requested_at      TIMESTAMP(6) WITH TIME ZONE,
    source_row_count               NUMBER(19),
    projection_row_count             NUMBER(19),
    mismatch_count                     NUMBER(19),
    build_owner                          VARCHAR2(256),
    last_error                              VARCHAR2(2000),
    updated_at                                 TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_projection_control PRIMARY KEY (projection_name),
    CONSTRAINT ck_projection_status CHECK (
        status IN ('NEEDS_REBUILD', 'BUILDING', 'READY')
    ),
    CONSTRAINT ck_projection_active_slot CHECK (active_slot IN ('A', 'B'))
);

INSERT INTO projection_control (
    projection_name, status, schema_version, active_slot, updated_at
) VALUES (
    'CALCULATION_READ_PROJECTION', 'NEEDS_REBUILD', 1, 'A', SYSTIMESTAMP
);

CREATE OR REPLACE VIEW calculation_transactional_view AS
SELECT c.id AS calculation_id,
       c.customer_id,
       cu.name AS customer_name,
       cu.email AS customer_email,
       seg.code AS segment_code,
       seg.name AS segment_name,
       c.price_table_id,
       pt.name AS price_table_name,
       c.status,
       NVL(li.line_item_count, 0) AS line_item_count,
       c.total_amount,
       NVL(li.rules_applied_count, 0) AS rules_applied_count,
       NVL(li.total_adjustment, 0) AS total_adjustment,
       c.requested_at,
       c.calculated_at
  FROM calculations c
  JOIN customers cu ON cu.id = c.customer_id
  JOIN customer_segments seg ON seg.id = cu.segment_id
  JOIN price_tables pt ON pt.id = c.price_table_id
  LEFT JOIN (
      SELECT calculation_id,
             COUNT(*) AS line_item_count,
             SUM(rules_applied_count) AS rules_applied_count,
             SUM(adjustment_amount) AS total_adjustment
        FROM calculation_line_items
       GROUP BY calculation_id
  ) li ON li.calculation_id = c.id;
