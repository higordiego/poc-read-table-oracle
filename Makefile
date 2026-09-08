## Oracle Memoptimized Read-Projection PoC
## Run `make` or `make help` to list every target.

.DEFAULT_GOAL := help

# Overridable on the command line, e.g. `make benchmark-load ROWS=500000`.
# Values set as real environment variables (`ROWS=500000 make benchmark-load`)
# take precedence over these defaults too.
APP_PORT          ?= 8080
ROWS              ?= 10000
ITERATIONS        ?= 50000
PLAN_ID           ?= 1
VUS               ?= 10
DURATION          ?= 60s
ORACLE_ADMIN_PASSWORD ?= OraclePwd123
ORACLE_APP_USER   ?= projection_app
ORACLE_APP_PASSWORD ?= ProjectionPwd123
ORACLE_SERVICE    ?= FREEPDB1
ORACLE_CONTAINER  ?= projection-oracle

BASE_URL := http://localhost:$(APP_PORT)
SQLPLUS_APP := docker exec -i $(ORACLE_CONTAINER) sqlplus -s $(ORACLE_APP_USER)/$(ORACLE_APP_PASSWORD)@//localhost:1521/$(ORACLE_SERVICE)
SQLPLUS_APP_IT := docker exec -it $(ORACLE_CONTAINER) sqlplus $(ORACLE_APP_USER)/$(ORACLE_APP_PASSWORD)@//localhost:1521/$(ORACLE_SERVICE)

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*?## "} \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5); next } \
		/^[a-zA-Z0-9_-]+:.*?## / { printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2 }' \
		$(MAKEFILE_LIST)
	@echo ""
	@echo "Variables (override with VAR=value): APP_PORT ROWS ITERATIONS PLAN_ID VUS DURATION"

##@ Environment

.PHONY: up down reset clean restart-app build ps logs wait-db
up: ## Build the app image and start the full stack
	docker compose up --build -d

down: ## Stop containers, keep the Oracle volume (data survives)
	docker compose down

reset: ## Full clean slate: wipe the Oracle volume and rebuild everything from scratch
	docker compose down -v
	docker compose up --build -d
	$(MAKE) wait-db
	docker compose up -d app

clean: ## Stop containers and delete the Oracle volume (irreversible data loss)
	docker compose down -v

restart-app: ## Rebuild and recreate only the app container -- keeps loaded Oracle data
	docker compose build app
	docker compose up -d app

build: ## Build the app image only, without starting anything
	docker compose build app

ps: ## Show container status
	docker compose ps

logs: ## Follow app and Oracle logs
	docker compose logs -f app oracle

wait-db: ## Poll FREEPDB1 until the listener accepts connections
	@echo "Waiting for $(ORACLE_SERVICE) to accept connections..."
	@for i in $$(seq 1 20); do \
		out=$$(docker exec $(ORACLE_CONTAINER) bash -c "echo 'SELECT 1 FROM dual;' | sqlplus -s system/$(ORACLE_ADMIN_PASSWORD)@//localhost:1521/$(ORACLE_SERVICE)" 2>/dev/null); \
		if echo "$$out" | grep -qE '^[[:space:]]*1[[:space:]]*$$'; then echo "$(ORACLE_SERVICE) is ready."; exit 0; fi; \
		echo "  not ready yet ($$i/20)..."; sleep 15; \
	done; \
	echo "Timed out waiting for $(ORACLE_SERVICE)." >&2; exit 1

##@ Quality

.PHONY: test test-integration coverage smoke
test: ## Unit tests (JUnit 5 + Mockito, no DB needed) via the Docker build stage
	docker build --target build -t oracle-read-projection-poc-test .

test-integration: ## Integration tests (Testcontainers + real Oracle) -- needs a local JDK/Maven and Docker
	mvn -Pintegration-test verify

coverage: ## Print where the JaCoCo coverage report is (run test-integration first)
	@echo "target/site/jacoco-merged/index.html  -- unit + integration combined (docs/testes.md Seção 0.1.1)"
	@echo "target/site/jacoco-unit/index.html     -- unit tests only"

smoke: ## Run scripts/smoke-test.sh against the running app
	./scripts/smoke-test.sh

##@ Projection admin

.PHONY: health status memoptimized plan validate rebuild
health: ## Check readiness and liveness
	curl -fsS $(BASE_URL)/actuator/health/readiness && echo
	curl -fsS $(BASE_URL)/actuator/health/liveness && echo

status: ## Show projection status (activeSlot, counts, mismatchCount)
	curl -fsS $(BASE_URL)/api/admin/projection/status && echo

memoptimized: ## Show memoptimize pool + table attribute status
	curl -fsS $(BASE_URL)/api/admin/projection/memoptimized && echo

plan: ## Show the Fast Lookup execution plan for PLAN_ID (default 1)
	curl -fsS $(BASE_URL)/api/admin/projection/plan/$(PLAN_ID) && echo

validate: ## Validate the projection against the transactional view (no rebuild)
	curl -fsS -X POST $(BASE_URL)/api/admin/projection/validate && echo

rebuild: ## Trigger a blue/green rebuild (flips activeSlot on success)
	curl -fsS -X POST $(BASE_URL)/api/admin/projection/rebuild && echo

##@ Data

.PHONY: benchmark-load benchmark db-size db-shell stats-gather
benchmark-load: ## Bulk-load ROWS calculations (default 10000) and rebuild the projection
	$(SQLPLUS_APP) @/opt/oracle/benchmark/01_load_data.sql $(ROWS)

benchmark: ## Run the A/B benchmark: ITERATIONS lookups over ROWS rows (defaults 50000 / 10000)
	$(SQLPLUS_APP) @/opt/oracle/benchmark/02_benchmark.sql $(ITERATIONS) $(ROWS)

db-size: ## Show total schema size on disk
	@printf 'SET LINES 200\nSELECT segment_name, segment_type, ROUND(bytes/1024/1024) AS mb FROM user_segments ORDER BY bytes DESC FETCH FIRST 10 ROWS ONLY;\nSELECT ROUND(SUM(bytes)/1024/1024/1024,3) AS total_gb FROM user_segments;\n' | $(SQLPLUS_APP)

stats-gather: ## Re-gather stats with a skew-aware histogram (see docs: customer_id skew)
	@printf "BEGIN\n  DBMS_STATS.GATHER_TABLE_STATS(USER, 'CALCULATIONS', method_opt => 'FOR ALL COLUMNS SIZE SKEWONLY');\n  DBMS_STATS.GATHER_TABLE_STATS(USER, 'CALCULATION_LINE_ITEMS');\nEND;\n/\n" | $(SQLPLUS_APP)

db-shell: ## Open an interactive SQL*Plus session as the app user
	$(SQLPLUS_APP_IT)

##@ Load testing (k6)

.PHONY: k6-smoke k6-test
k6-smoke: ## Quick k6 sanity run (3 VUs, 10s) -- read-after-write consistency under light load
	BASE_URL=$(BASE_URL) VUS=3 DURATION=10s k6 run k6/read-after-write-test.js

k6-test: ## Full k6 load test (VUS/DURATION, defaults 10 / 60s) -- read-after-write, search, projection consistency
	BASE_URL=$(BASE_URL) VUS=$(VUS) DURATION=$(DURATION) k6 run k6/read-after-write-test.js

##@ Reference

.PHONY: postman docs swagger
postman: ## Print where the Postman collection lives
	@echo "postman/oracle-read-projection-poc.postman_collection.json"
	@echo "Import into Postman: File -> Import. baseUrl defaults to $(BASE_URL)."

swagger: ## Print the Swagger UI / OpenAPI URLs (app must be running)
	@echo "Swagger UI:  $(BASE_URL)/swagger-ui/index.html"
	@echo "OpenAPI doc: $(BASE_URL)/v3/api-docs"
	@echo "No auth, same as the rest of the app -- don't expose outside a controlled environment."

docs: ## Print where the docs live
	@echo "README.md                        -- quickstart, curl examples, benchmark usage, Swagger UI"
	@echo "docs/README.md                   -- index of all docs, with per-doc menus"
	@echo "docs/architecture.md             -- design decisions, trade-offs, known limits"
	@echo "docs/documento-executivo-poc.md  -- executive summary, results, rollout timeline"
	@echo "docs/implementacao.md            -- real code walkthrough, layer by layer"
	@echo "docs/testes.md                   -- every tested claim, exact command + real output"
	@echo "docs/fluxos-e-ciclo-de-vida.md   -- hypothesis/proof, versioning, CI/CD, business flow"
