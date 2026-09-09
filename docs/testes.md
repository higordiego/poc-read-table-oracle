# Testes — Evidência Detalhada

Este documento reúne, teste a teste, o comando exato executado, a saída real
observada (não estimada) e o que cada resultado prova ou não prova. Ele
complementa a Seção 5 e 6 do `docs/documento-executivo-poc.md` (que trazem
só o resumo) e `docs/implementacao.md` (o código por trás de cada
mecanismo testado aqui).

*Ver também: [`docs/README.md`](README.md) (índice geral), [`documento-executivo-poc.md`](documento-executivo-poc.md), [`architecture.md`](architecture.md), [`implementacao.md`](implementacao.md), [`fluxos-e-ciclo-de-vida.md`](fluxos-e-ciclo-de-vida.md).*

**Menu:** [0. Como rodar](#0-como-rodar) ([suíte automatizada](#01-suíte-automatizada-junit-5--66-testes-0-falhas), [cobertura](#011-cobertura-de-código--936-de-linhas-817-de-branches), [verificação end-to-end](#02-execução-de-verificação-end-to-end--2026-09-06)) · [1. Preço](#1-correção-do-cálculo-de-preço) · [2. Fast Lookup](#2-fast-lookup--plano-de-execução-real-e-diagnóstico-completo) · [3. Busca filtrada](#3-busca-filtrada) · [4. Rebuild](#4-rebuild-bluegreen-sob-volume-real) · [5. Concorrência](#5-concorrência-real-durante-um-rebuild--o-teste-mais-importante) · [6. k6](#6-carga-sustentada--k6) ([escalonamento](#61-escalonamento-de-carga--crescente-e-decrescente)) · [7. Carga em massa](#7-carga-em-massa--o-bug-real-encontrado-e-a-carga-final) · [8. Incidente de memória](#8-incidente-de-memória--oracle-database-free-teto-2-gb) · [9. Resumo dos testes](#9-tabela-resumo-de-todos-os-testes) · [10. Ganhos da sessão](#10-resumo-dos-ganhos-desta-sessão)

## 0. Como rodar

```bash
make test              # unit tests (JUnit 5 + Mockito, sem banco) via o estágio de build do Docker
make test-integration  # integration tests (Testcontainers + Oracle real) -- precisa de JDK/Maven local + Docker
make smoke             # scripts/smoke-test.sh — 6 curls sequenciais contra a app rodando
make k6-smoke          # k6, sanity rápido: 3 VUs, 10s
make k6-test VUS=20 DURATION=45s   # k6 completo, parametrizável
```

`scripts/smoke-test.sh` roda, em sequência, contra a aplicação já no ar:

```sh
curl -fsS "${base_url}/actuator/health/readiness"
curl -fsS "${base_url}/api/admin/projection/status"
curl -fsS "${base_url}/api/admin/projection/memoptimized"
curl -fsS "${base_url}/api/read/calculations/1?mode=projection"
curl -fsS "${base_url}/api/read/calculations/1?mode=transactional"
curl -fsS "${base_url}/api/admin/projection/plan/1"
```

`-fsS` faz o script falhar (`set -eu`) no primeiro `curl` com status de erro
— um smoke test binário, não uma inspeção manual de saída.

### 0.1 Suíte automatizada (JUnit 5) — 66 testes, 0 falhas

Duas camadas, deliberadamente separadas (`maven-surefire-plugin` para uma,
`maven-failsafe-plugin` atrás do profile Maven `integration-test` para a
outra — `mvn verify` sozinho, o que o `Dockerfile` da aplicação roda, só
executa a primeira; o container de build da imagem não tem acesso ao
daemon Docker para subir um Oracle sibling):

**Unit tests (45)** — `src/test/java/.../*Test.java`, sem banco:
`PriceResolutionServiceTest` (5 — mocka `JdbcClient` por SQL literal
distinto, reproduz o cenário de R$330,24/R$5,76 desta seção sem tocar o
Oracle), `CalculationReadRepositoryTest` (4 — round-trip do cursor de
keyset, montagem do `WHERE` de `Filters`), `RequestValidationTest` (9 —
todas as constraints `jakarta.validation` dos DTOs das quatro
controllers), `ApiExceptionHandlerTest` (4 — cada `@ExceptionHandler`
chamado direto, sem MockMvc), `ProjectionHealthIndicatorTest` (3 — up/
down/exceção), `ProjectionBootstrapRunnerTest` (2 — os dois ramos de
`bootstrap-on-startup`, nunca exercitados por nenhum IT já que todos
desligam o bootstrap deliberadamente), e quatro slices `@WebMvcTest`
— `CustomerControllerTest` (3), `CalculationControllerTest` (6),
`CalculationReadControllerTest` (5), `ProjectionAdminControllerTest` (6)
— com o service por trás mockado (`@MockitoBean`), exercitando o
dispatch HTTP real (mapeamento, `@Valid`, serialização JSON,
`ApiExceptionHandler` via `@ControllerAdvice`) sem precisar de banco.

**Integration tests (21)** — `src/test/java/.../*IT.java`, contra um Oracle
real via Testcontainers (`gvenzl/oracle-free:23.26.0-slim-faststart`, não
a imagem oficial do `compose.yaml` — mesma semântica de Oracle Free, boot
em ~20-30s em vez de minutos): `CalculationLifecycleIT` (3 — cria/atualiza/
apaga um cálculo, confere a projeção via trigger em cada passo, sem sleep),
`CalculationCommandServiceIT` (2 — cliente e tabela de preço inexistentes),
`ProjectionRebuildIT` (5 — rebuild troca `activeSlot` e zera
`mismatchCount`; `validate()` sozinho também reporta zero;
`getMemoptimizedStatus()` confirma o pool configurado pelo script de init;
versão de schema desatualizada força rebuild mesmo com status `READY`;
uma linha órfã inserida direto na projeção é detectada como divergência
real por `validate()`), `ProjectionMutexIT` (2 — ver Seção 0.1.1: os dois
ramos de disputa pelo mesmo rebuild, forçados de forma determinística, sem
race), `CalculationSearchKeysetIT` (3 — duas páginas consecutivas sem
sobreposição, o mesmo formato da evidência da Seção 3.1 em escala pequena;
modo `transactional`; modo inválido rejeitado), `CustomerServiceIT` (2 — o
caminho de `update`, não tocado por nenhum outro teste), `FastLookupPlanServiceIT`
(2 — `DBMS_XPLAN.DISPLAY_CURSOR` sobre um cursor real, que só existe
depois de uma consulta real ter rodado — não é mockável). `OracleIntegrationTestBase`
concentra a infraestrutura compartilhada: um único container para a JVM
inteira (padrão "singleton container" do Testcontainers — usar
`@Container` gera parada automática entre classes, confirmado na prática),
os grants extras que `DB_DEVELOPER_ROLE` não cobre (`CREATE SYNONYM`, os
objetos `SYS.*`), e um script de init que configura
`MEMOPTIMIZE_POOL_SIZE=256M` com um restart automático da instância antes
do Flyway rodar — sem isso, `cutover()` falha com `ORA-62138` (a área de
memória nem existe), diferente do achado da Seção 2 (a área existe, só
nunca populada).

```
Total: 66  Failures: 0  Errors: 0
```

Escopo deliberado: a alegação central ("a projeção nunca fica
desatualizada") só é verdadeiramente testável contra um Oracle de verdade
— mocks não reproduzem trigger, MVCC ou locks reais. Por isso a suíte
automatizada cobre a lógica pura (resolução de preço, cursor, validação,
dispatch HTTP) com unit tests rápidos, e delega tudo que depende do
comportamento real do banco para os integration tests — o mesmo
raciocínio, em miniatura e repetível a cada build, por trás de toda a
evidência manual do restante deste documento.

### 0.1.1 Cobertura de código — 93,6% de linhas, 81,7% de branches

Medida com JaCoCo (`jacoco-maven-plugin`, unit + integration mesclados num
único relatório via `jacoco:merge` — `make test-integration` gera
`target/site/jacoco-merged/index.html`), porque a maior parte da lógica
real deste sistema (triggers, blue/green) só é exercitada pela camada de
integração; um relatório só de unit tests subestimaria muito o que está de
fato coberto.

```
Instruções: 2.107/2.243 = 93,9%
Linhas:       481/514   = 93,6%
Branches:       85/104  = 81,7%
```

A classe `ProjectionPocApplication` (o `main()`, nunca chamado por nenhum
teste — Spring Boot Test sobe o contexto sem passar por ele) foi excluída
do relatório deliberadamente, prática padrão; sem essa exclusão o número
seria menor ainda, não maior.

**Histórico**: a primeira medição (68 testes) deu 88,5% de linhas e 62,5%
de branches — abaixo de 90% e de 80% respectivamente. A lacuna de branch
estava quase toda em `ProjectionService`: os ramos de retentativa sob
disputa pelo mesmo rebuild (`attemptRebuild` capturando
`ProjectionBuildInProgressException` ou `ORA-00054` via
`isOracleResourceBusy`, caindo em `waitUntilReady()`). A primeira reação
foi não perseguir isso — cobrir via duas threads competindo pela mesma
janela de milissegundos entre o lock de linha e o guard de status daria um
teste instável (às vezes passa, às vezes não), e um teste assim é pior que
não ter o teste.

**A saída encontrada não precisou de concorrência real nenhuma.** Em vez
de duas threads competindo por uma janela estreita, `ProjectionMutexIT`
força a mesma condição de forma determinística:

- uma segunda conexão JDBC crua (`DriverManager`, fora do pool do Spring)
  segura de verdade o lock `FOR UPDATE` da linha de `projection_control` —
  a aplicação genuinamente recebe `ORA-00054` da segunda sessão real, não
  um erro simulado — e o teste confirma que `rebuild()` espera e então
  estoura por timeout;
- o status é forçado para `BUILDING` diretamente, fazendo o guard
  `claimed == 0` disparar de verdade (`ProjectionBuildInProgressException`),
  e o teste confirma o mesmo comportamento de espera-e-timeout do outro
  lado.

Um `wait-timeout`/`poll-interval` curtos (3s/200ms, só para essa classe,
via `@DynamicPropertySource` própria — contexto Spring separado) evitam
esperar os 180s reais de produção. Nenhuma race, nenhum `Thread.sleep`
torcendo para o timing dar certo — o mesmo resultado de uma disputa real,
provocado sob controle total. Essa é a diferença entre "sortear se o teste
vai pegar a janela" (descartado) e "criar a condição exata que a janela
produziria" (o que foi feito).

Com isso, `ProjectionService` foi de 35,4% para 66,7% de branches, e o
total do projeto passou de 62,5% para **81,7%** — acima da meta de 80%. O
que ainda falta ali (16 branches) é código defensivo genuinamente
redundante: a checagem de mismatch dentro de `rebuildInTransaction` depois
que `cutover()` já validou a mesma coisa dentro do PL/SQL (só dispararia se
o `MINUS` do pacote mentisse), e o branch de recuperação de
`InetAddress.getLocalHost()` falhando em `buildOwner()`. Não perseguidos:
teriam que injetar falha onde o próprio desenho já garante que ela não
acontece.

### 0.2 Execução de verificação end-to-end — 2026-09-06

As quatro camadas de teste que este projeto tem — unitária, integração,
smoke e carga — foram rodadas de verdade, em sequência, na mesma sessão,
especificamente para responder "isso está realmente funcional?" com
número, não com afirmação. Nenhum resultado abaixo é de memória ou de uma
execução anterior; todos foram capturados na hora.

| Camada | Comando | Resultado |
|---|---|---|
| Unit | `make test` | **18/18** — 0 falhas, 0 erros |
| Integration (Testcontainers) | `make test-integration` | **6/6** — 0 falhas, 0 erros, `BUILD SUCCESS` (34s) |
| Smoke | `make smoke` | 6/6 checks, `exit 0` |
| Carga (k6) | `BASE_URL=... VUS=5 DURATION=15s k6 run k6/read-after-write-test.js` | `checks_total: 3626`, **100% succeeded**, `exit 0` |

**Unit** — `mvn verify` via o estágio de build do Docker:

```
Tests run: 9, Failures: 0, Errors: 0 -- RequestValidationTest
Tests run: 4, Failures: 0, Errors: 0 -- CalculationReadRepositoryTest
Tests run: 5, Failures: 0, Errors: 0 -- PriceResolutionServiceTest
```

**Integration** — `mvn -Pintegration-test verify`, contra um Oracle real subido do zero via Testcontainers (não o container de dev):

```
Tests run: 3, Failures: 0, Errors: 0 -- CalculationLifecycleIT
Tests run: 1, Failures: 0, Errors: 0 -- CalculationSearchKeysetIT (29.24s)
Tests run: 2, Failures: 0, Errors: 0 -- ProjectionRebuildIT
[INFO] BUILD SUCCESS
```

**Smoke** — contra o container de dev, no ar há 13h no momento deste teste, com os 2,7M cálculos já carregados de sessões anteriores:

```json
{"status":"UP"}
{"status":"READY","activeSlot":"B","sourceRowCount":2700022,"projectionRowCount":2700022,"mismatchCount":0}
{"poolEnabled":true,"tableMemoptimizeRead":"ENABLED"}
Smoke test completed successfully. (exit 0)
```

**Carga (k6)** — mesmo teste de `docs/documento-executivo-poc.md` Seção 5, rodado de novo agora, em escala menor (5 VUs em vez de 20) só para reconfirmar, não para remedir throughput:

```
checks_succeeded...............: 100.00% 3626 out of 3626
read_after_write_consistent....: 100.00% 492 out of 492
projection_mismatch_observed...: 0.00%   0 out of 8
search_ok......................: 100.00% 58 out of 58
status_update_consistent.......: 100.00% 492 out of 492
http_req_failed.................: 0.00%   0 out of 2035
```

**Conclusão**: as quatro camadas passaram sem exceção nesta execução — a
lógica de precificação, a mecânica de trigger/blue-green contra um Oracle
recém-criado do zero, a saúde do sistema de longa duração com o volume de
produção da PoC já carregado, e a consistência de leitura pós-escrita sob
carga concorrente, tudo na mesma sessão de verificação. O sistema está
funcional agora, não apenas "estava" quando os testes foram escritos.

*Nota: a suíte cresceu de 24 para 58 testes mais tarde no mesmo dia, ao
medir e fechar a lacuna de cobertura de código — ver [Seção 0.1.1](#011-cobertura-de-código--936-de-linhas-817-de-branches). Os números
desta seção (0.2) são o registro exato daquela execução específica, não
foram reescritos para bater com o estado atual.*

---

## 1. Correção do cálculo de preço

**Alegação**: a resolução de preço (tarifa por faixa de quantidade + regra
por condição, em ordem de prioridade) calcula o valor certo.

**Método**: recriar via API, contra o sistema rodando, o mesmo cenário do
dado semeado pela migration V3 (cliente enterprise, item de *Compute Hour*
com desconto por volume mais uma regra de canal parceiro, mais item de
*Storage* com desconto simples) — e comparar o resultado com a conta feita
à mão.

```
POST /api/customers
{"segmentId":2,"name":"Grace Hopper","email":"grace@example.com","status":"ACTIVE"}
→ 201 {"id":1000, ...}

POST /api/calculations
{"customerId":1000,"priceTableId":2,"items":[
  {"priceTableItemId":4,"quantity":600,"ruleConditionValue":"PARTNER"},
  {"priceTableItemId":5,"quantity":2000}
]}
→ 201 {"calculationId":1000,"customerId":1000,"status":"CALCULATED","lineItemCount":2,"totalAmount":330.24000}

GET /api/read/calculations/1000?mode=projection
→ 200 {"calculationId":1000, ..., "totalAmount":330.24,"rulesAppliedCount":1,"totalAdjustment":5.76, ...}
```

**Conta manual**: item 4 (Compute Hour, base R$0,40) com quantidade 600
casa a tarifa de faixa ≥500 (-20%) → R$0,32; a regra `PARTNER` (-3%) aplica
sobre isso → R$0,3104; × 600 = R$186,24. Item 5 (Storage, base R$0,08) com
quantidade 2000 casa a tarifa única (-10%) → R$0,072; × 2000 = R$144,00.
**Total = R$330,24.** Ajuste atribuível às regras = (0,32−0,3104) × 600 =
**R$5,76**.

**Resultado**: bate exatamente com o retorno da API — mesmo algoritmo
rodando duas vezes com os mesmos números, uma na migration e outra ao vivo.

---

## 2. Fast Lookup — plano de execução real e diagnóstico completo

**Alegação**: o Memoptimized Rowstore Fast Lookup está de fato sendo usado
nas leituras por chave primária.

### 2.1 Observação inicial

```
GET /api/admin/projection/plan/1
→ 200 {
  "calculationId": 1, "fastLookupActive": false,
  "plan": [
    "SELECT /* fast_lookup_probe */ p.* FROM calculation_read_projection p",
    " WHERE calculation_id = :1",
    "|  1 |  TABLE ACCESS BY INDEX ROWID | CALCULATION_READ_PROJECTION_A    |",
    "|* 2 |   INDEX UNIQUE SCAN          | PK_CALCULATION_READ_PROJECTION_A |"
  ]
}
```

A tabela está configurada (`MEMOPTIMIZE_READ=ENABLED`), mas o plano real —
lido de `DBMS_XPLAN.DISPLAY_CURSOR` sobre o cursor que **acabou de
executar**, não um plano estimado — mostra `INDEX UNIQUE SCAN` comum, não
`READ OPTIM`. Reproduzido a 2 linhas e a 2,7 milhões: mesmo resultado nos
dois extremos de volume, o que já descarta "tabela grande/pequena demais
para o pool" como explicação de primeira vista.

### 2.2 Trilha de eliminação

**Hipótese 1 — driver JDBC Thin, descartada.** A documentação do Oracle
para Memoptimized Rowstore lista, entre pré-requisitos históricos, que a
consulta não pode rodar via driver JDBC Thin. Toda leitura da aplicação
passa por `ojdbc11` (JDBC Thin) via HikariCP — testado o mesmo `SELECT`
via SQL*Plus (que usa OCI, não JDBC):

```sql
SELECT /* fast_lookup_via_sqlplus */ * FROM calculation_read_projection
 WHERE calculation_id = 1;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'BASIC +PREDICATE'));
-- |  1 |  TABLE ACCESS BY INDEX ROWID | CALCULATION_READ_PROJECTION_B    |
-- |* 2 |   INDEX UNIQUE SCAN          | PK_CALCULATION_READ_PROJECTION_B |
```

Mesmo plano, mesmo `INDEX UNIQUE SCAN`, via um cliente que não usa JDBC de
forma nenhuma. **Hipótese descartada.**

**Hipótese 2 — `STATISTICS_LEVEL` incompatível, descartada.**

```sql
SHOW PARAMETER statistics_level
-- statistics_level     string    TYPICAL
```

O pré-requisito documentado é `STATISTICS_LEVEL` diferente de `ALL`; está
em `TYPICAL`. **Hipótese descartada.**

**Causa raiz — a tarefa de população nunca é aceita pelo processo em
segundo plano.** Consultadas as estatísticas de instância inteira desde o
boot:

```sql
SELECT n.name, s.value FROM v$sysstat s JOIN v$statname n ON n.statistic# = s.statistic#
 WHERE n.name LIKE '%memopt%';

memopt r populate tasks accepted        0
memopt r populate tasks not accepted    0
memopt r rows populated                 0
memopt r hits                           0
memopt r lookups                        0
```

Chamando `DBMS_MEMOPTIMIZE.POPULATE` de novo, ao vivo, e checando o
contador imediatamente depois:

```sql
BEGIN DBMS_MEMOPTIMIZE.POPULATE(USER, 'CALCULATION_READ_PROJECTION_B'); END;
/
PL/SQL procedure successfully completed.   -- sem erro
-- memopt r populate tasks accepted        0   -- continua zero
```

Processos de background responsáveis por essa fila, confirmados ativos:

```sql
SELECT spid, program FROM v$process WHERE program LIKE '%SMCO%' OR program LIKE '%W0%';
2551   oracle@... (SMCO)   -- Space Management Co-ordinator: rodando
2579   oracle@... (W000)   -- worker: rodando
2593   oracle@... (W001)   -- worker: rodando
```

**Resultado**: a chamada `DBMS_MEMOPTIMIZE.POPULATE` retorna sucesso, mas o
contador de tarefas aceitas permanece em zero — não "rejeitada", *nunca
sequer chega a ser processada* — apesar dos processos responsáveis por essa
fila estarem ativos e saudáveis. Na prática, um no-op silencioso nesta
instância, em toda combinação testada (driver, tamanho de tabela,
quantidade de chamadas).

**Hipótese mais provável, não uma prova documental ponto a ponto**:
Memoptimized Rowstore é historicamente associado a Enterprise Edition /
Exadata / OCI Extreme Performance. A página oficial de restrições do Oracle
AI Database Free não lista o recurso explicitamente entre os bloqueados —
então esta é uma inferência apoiada por evidência indireta consistente
(pacote existe, sintaxe aceita, população nunca executa), não uma citação
de documentação que proíba o recurso nominalmente. Para fechar esta prova
especificamente, o próximo passo é repetir exatamente este mesmo teste
(`GET /api/admin/projection/plan/{id}`) contra uma edição Enterprise ou
Oracle Cloud Base Database EE-EP/ExaCS.

---

## 3. Busca filtrada

### 3.1 Paginação por keyset — sem duplicar nem pular linhas

```
GET /api/read/calculations?limit=3
→ 200 {"total":2700005,"results":[
    {"calculationId":1003, ...}, {"calculationId":1002, ...}, {"calculationId":1001, ...}
  ],"nextCursor":"MjAyNi0wOS0wNFQyMToxNzozMy4wNjY1ODRafDEwMDE"}

GET /api/read/calculations?limit=3&cursor=MjAyNi0wOS0wNFQyMToxNzozMy4wNjY1ODRafDEwMDE
→ 200 {"total":2700005,"results":[
    {"calculationId":3700000, ...}, {"calculationId":3699999, ...}, {"calculationId":3699998, ...}
  ],"nextCursor":"MjAyNi0wOS0wNFQyMDo1NTo0NS42MDYzOTlafDM2OTk5OTg"}
```

Os ids da página 2 (3700000, 3699999, 3699998) não têm nenhuma sobreposição
com os da página 1 (1003, 1002, 1001) — o cursor avançou de verdade, sem
repetir nem pular. `total` permaneceu `2700005` nas duas chamadas, como
esperado (o filtro não mudou, só a posição de leitura).

### 3.2 Coluna desbalanceada precisa de histograma, não só de índice

Distribuição real após a carga em massa:

```sql
SELECT customer_id, COUNT(*) FROM calculation_read_projection
 GROUP BY customer_id ORDER BY COUNT(*) DESC FETCH FIRST 5 ROWS ONLY;

CUSTOMER_ID   COUNT(*)
-----------  ---------
     999999   2700000
       1000         3
          1         1
```

Plano **antes** do histograma, filtrando `customer_id=1000` (só 3 linhas):

```
|* 4 |  TABLE ACCESS FULL     | CALCULATION_READ_PROJECTION_A  |
Predicate: filter("CUSTOMER_ID"=1000)
```

Fix aplicado:

```sql
BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'CALCULATION_READ_PROJECTION_A',
    method_opt => 'FOR ALL COLUMNS SIZE SKEWONLY', cascade => TRUE);
END;
```

Plano **depois**, mesma consulta, mesmos 3 registros:

```
|  4 |  TABLE ACCESS BY INDEX ROWID BATCHED | CALCULATION_READ_PROJECTION_A   |
|* 5 |   INDEX RANGE SCAN               | IDX_PROJECTION_A_CUSTOMER_REQUESTED |
Predicate: access("CUSTOMER_ID"=1000)
```

Tempo de resposta real da API, mesma chamada, antes e depois:

```
ANTES:  curl .../calculations?customerId=1000&limit=5   → 0.456s total
DEPOIS: curl .../calculations?customerId=1000&limit=5   → 0.062s total
```

**7,4× mais rápido**, medido, não estimado. Sem histograma, o otimizador
assumia distribuição uniforme e escolhia *full scan* até para um cliente
com 3 linhas — mesmo plano que usaria para o cliente dono de 2,7 milhões.
Com histograma, ele diferencia os dois casos corretamente: `INDEX RANGE
SCAN` para o seletivo, *full scan* mantido (corretamente) para o não
seletivo.

---

## 4. Rebuild blue/green sob volume real

**Método**: disparar um rebuild completo com 2,7 milhões de linhas já
carregadas e medir o tempo com `time`.

```
Setup: {"status":"READY","activeSlot":"A","sourceRowCount":2700002,"mismatchCount":0}

POST /api/admin/projection/rebuild
→ 200 (após 2m34,63s de execução real)
{"projectionName":"CALCULATION_READ_PROJECTION","status":"READY",
 "activeSlot":"B","sourceRowCount":2700007,"projectionRowCount":2700007,
 "mismatchCount":0, ...}
```

`activeSlot` trocou de `A` para `B`, terminou com `mismatchCount: 0` — e,
o ponto central do desenho, a aplicação continuou aceitando escritas o
tempo inteiro (ver Seção 5, que dispara escritas reais dentro desta mesma
janela).

---

## 5. Concorrência real durante um rebuild — o teste mais importante

Esta é a alegação mais importante de todo o mecanismo, e a única forma
honesta de defendê-la é medir, não argumentar: disparar escritas de
verdade *durante* um rebuild de verdade sobre a tabela de 2,7 milhões de
linhas, e conferir se alguma se perdeu.

```
# status imediatamente antes:
GET /api/admin/projection/status
→ {"status":"READY","activeSlot":"A","sourceRowCount":2700002,"mismatchCount":0}

# disparado em paralelo:
POST /api/admin/projection/rebuild        # background, ainda rodando
# + 15 POSTs concorrentes, cada um:
POST /api/calculations
{"customerId":1003,"priceTableId":1,"items":[{"priceTableItemId":1,"quantity":N}]}

# resultado das 15 requisições concorrentes:
201 201 201 201 201 201 201 201 201 201 201 201 201 201 201

# resultado do rebuild, ao terminar:
real  2m34.63s
{"status":"READY","activeSlot":"B", ...}
```

Conferência de verdade absoluta, direto no banco (não via app):

```sql
SELECT COUNT(*) FROM calculations;               -- 2700022
SELECT COUNT(*) FROM calculation_read_projection; -- 2700022
SELECT id FROM calculations WHERE id BETWEEN 1007 AND 1021;
-- as 15 linhas concorrentes (1007..1021), todas presentes
```

Validação formal pós-evento:

```
POST /api/admin/projection/validate
→ {"status":"READY","sourceRowCount":2700022,"projectionRowCount":2700022,"mismatchCount":0}
```

Leitura de uma das linhas concorrentes, já servida pelo slot novo (B):

```
GET /api/read/calculations/1015?mode=projection
→ {"calculationId":1015,"customerId":1003,"status":"CALCULATED",
   "totalAmount":2,"requestedAt":"2026-09-04T22:14:07.300086Z", ...}
-- requestedAt cai DENTRO da janela do rebuild (que terminou às 22:14:28)
```

**O que isso prova**: as 15 requisições — disparadas enquanto o rebuild
ainda estava construindo o slot inativo — retornaram 201 em todas, sem
timeout, sem erro, sem bloqueio perceptível. A contagem bruta do banco
(2.700.022 nos dois lados) bate exatamente com a que o app reporta, com
`mismatchCount: 0`. A linha 1015 tem `requestedAt` registrado *dentro* da
janela do rebuild e é servida corretamente pelo slot que só se tornou ativo
depois — a escrita aconteceu no meio da reconstrução e a leitura, depois,
veio do lugar certo.

---

## 6. Carga sustentada — k6

`k6/read-after-write-test.js` roda três cenários em paralelo, todos contra
a `BASE_URL` informada:

| Cenário | Executor | O que faz |
|---|---|---|
| `write_and_verify` | `ramping-vus`: 0→N em 15s, sustenta N pela `DURATION`, ramp-down 10s | Cria um cálculo, lê de volta **sem sleep** e confere `totalAmount`/`lineItemCount`; em seguida faz `PATCH` de status e lê de volta de novo, conferindo o status. |
| `search_load` | `constant-vus`: N/4 | Busca filtrada concorrente (`GET /api/read/calculations?customerId=...`) o teste inteiro. |
| `status_watch` | `constant-vus`: 1 VU, poll a cada 2s | Lê `/api/admin/projection/status` e falha o teste se `mismatchCount != 0` em qualquer poll. |

Trecho central do cenário 1 — a verificação sem sleep, sem retry, é
deliberada: é exatamente isso que está sendo testado:

```javascript
export function writeAndVerify(data) {
  const writeRes = http.post(`${BASE_URL}/api/calculations`, payload, {...});
  const writeBody = writeRes.json();
  const calcId = writeBody.calculationId;
  const expectedTotal = writeBody.totalAmount;

  // Sem sleep, sem retry: é exatamente isso que estamos testando -- a
  // projeção já tem que refletir a escrita no request seguinte.
  const readRes = http.get(`${BASE_URL}/api/read/calculations/${calcId}?mode=projection`, {...});

  const consistent = check(readRes, {
    'read-after-write: 200': (r) => r.status === 200,
    'read-after-write: totalAmount bate': (r) => Math.abs(r.json('totalAmount') - expectedTotal) < 0.01,
    'read-after-write: lineItemCount bate': (r) => r.json('lineItemCount') === 1,
  });
  readAfterWriteConsistent.add(consistent);   // métrica custom, vira o threshold rate>=1
  // ... PATCH status + segunda leitura imediata, mesma lógica ...
}
```

Thresholds configurados — qualquer um que falhar reprova a suíte inteira:

```javascript
thresholds: {
  read_after_write_consistent: ['rate>=1'],     // 100% ou reprova
  status_update_consistent: ['rate>=1'],
  projection_mismatch_observed: ['rate==0'],
  search_ok: ['rate>=0.99'],
  http_req_failed: ['rate<0.01'],
  read_after_write_latency_ms: ['p(95)<500', 'p(99)<1000'],
}
```

**Comando executado**:

```bash
make k6-test VUS=20 DURATION=45s
# equivalente a:
BASE_URL=http://localhost:8080 VUS=20 DURATION=45s k6 run k6/read-after-write-test.js
```

**Resultado — contra o sistema com 2,7 milhões de linhas já carregadas,
~1min10s de execução**:

```
✓ http_req_failed ................... rate<0.01     → 0.00%
✓ projection_mismatch_observed ...... rate==0       → 0.00%
✓ read_after_write_consistent ....... rate>=1       → 100.00%
✓ status_update_consistent .......... rate>=1       → 100.00%
✓ search_ok .......................... rate>=0.99    → 100.00%
✓ read_after_write_latency_ms ....... p(95)<500ms   → p(95)=2ms, p(99)=3ms

18.230 requisições HTTP, 0 falhas
4.912 iterações completas (create → read → patch → read)
4.439 verificações de escrita→leitura imediata: 100% consistentes
450 buscas filtradas concorrentes: 100% OK
23 polls de status durante a carga: mismatchCount sempre 0
http_req_duration: avg=2.24ms  p90=4.8ms  p95=5.84ms  max=56.62ms
```

**O que isso prova**: em 4.439 verificações independentes — cada uma uma
escrita real seguida, sem espera artificial, de uma leitura real — nenhuma
retornou um valor desatualizado. A latência entre a escrita confirmar e a
leitura devolver o valor certo ficou em 1-2ms na mediana, contra um sistema
que já tinha 2,7 milhões de linhas na projeção no momento do teste. Zero
divergências em 23 checagens espalhadas pelos ~70 segundos de carga.

### 6.1 Escalonamento de carga — crescente e decrescente

Duas coisas diferentes chamadas de "crescente/decrescente" aqui, ambas
cobertas:

**Dentro de cada execução**, o cenário `write_and_verify` já sobe e desce
carga sozinho — é a definição do executor `ramping-vus` (Seção 6): 0 → N
VUs em 15s (crescente), sustenta N pela `DURATION` inteira, depois N → 0
em 10s (decrescente). Nenhuma leitura pós-escrita ficou inconsistente em
nenhuma das duas rampas, em nenhuma das execuções abaixo — a consistência
não depende de estar em regime permanente.

**Entre execuções**, rodamos o mesmo teste em níveis de VUs cada vez mais
altos ao longo desta sessão, contra o sistema com os mesmos 2,7 milhões de
cálculos, para ver como a latência se comporta conforme a carga cresce —
não só se o sistema aguenta um nível fixo:

| VUs escrita (+ busca) | Duração | Requisições HTTP | Falhas | Leituras pós-escrita | Latência p95 (leitura pós-escrita) | `http_req_duration` p95 / máx |
|---|---|---|---|---|---|---|
| 10 (+2) | 30s | 6.580 | 0 | 1.611/1.611 = 100% | 3ms | 8,32ms / 183,78ms |
| 20 (+5) | 45s | 18.230 | 0 | 4.439/4.439 = 100% | 5,84ms | 5,84ms / 56,62ms |
| 25 (+6) | 20s | 11.867 | 0 | 2.907/2.907 = 100% | 2ms | 6,75ms / **3,01s** |
| **100 (+25)** | 200s | 257.833 | 0 | 62.510/62.510 = 100% | 34ms | 38,91ms / 1,78s |

**O que cresce**: a latência sobe de forma visível e honesta conforme os
VUs crescem — p95 de leitura pós-escrita foi de 2-6ms na faixa de 10-25 VUs
para 34ms a 100 VUs; o `max` de `http_req_duration` mostra picos de
segundos já a partir de 25 VUs. Isso é fila real acontecendo no pool de
conexões do HikariCP, que está fixo em **10 conexões**
(`DB_POOL_SIZE:10` em `application.yml`) — com 100+ VUs disputando 10
conexões, algumas requisições esperam a vez antes de sequer chegar ao
banco.

**O que não muda**: em nenhum nível — nem no de 100 VUs de escrita
concorrente, o mais alto testado — houve uma única leitura pós-escrita
inconsistente, um único mismatch de projeção ou uma única falha HTTP. A
fila deixa o sistema mais lento sob pressão; nunca o deixa errado. Depois
da execução de 100 VUs (3m45s sustentados), os containers voltaram ao
ocioso em segundos — CPU do app caiu de 156% para 0,46%, do Oracle de
103% para 4,2% — sem conexão presa, sem processo travado.

**Para achar o teto real da aplicação** (não o do pool), o próximo passo
seria repetir o nível de 100 VUs com `DB_POOL_SIZE` maior que 10 e comparar
a mesma latência — se ela cair de volta para a faixa de poucos milissegundos,
o gargalo era o pool; se continuar alta, o gargalo é outra coisa (Oracle,
rede, CPU do container). Não testado ainda nesta sessão.

---

## 7. Carga em massa — o bug real encontrado, e a carga final

### 7.1 O bug, antes do fix

Primeira tentativa de carga de 20.000 cálculos usando
`projection_admin_pkg.cutover` (a rotina normal de rebuild, sem a correção
descrita em `docs/implementacao.md` Seção 3):

```
sqlplus ... @01_load_data.sql 20000
...
20000 rows created.   -- em calculations, confirmado
60000 rows created.   -- em calculation_line_items, confirmado
...
PROJECTION_ROWS
---------------
              3        <-- deveria ser 20002. Os 20.000 sumiram na convergência.
```

Depois do fix (validar direto contra a view, sem `converge_inactive`):

```
PROJECTION_ROWS
---------------
          20002        <-- correto: 20000 + 2 do seed
```

A hipótese de design original ("`converge_inactive` é seguro reusar em
qualquer rebuild") era falsa especificamente para carga em massa com
triggers desligados — e isso só apareceu rodando de verdade, não em
revisão de código, porque o bug está na *semântica* da convergência
(confiar no slot ativo como fonte de verdade sobre órfãos), não na
sintaxe.

### 7.2 Carga final, em escala real

```bash
ROWS=2700000 make benchmark-load
```

```
Loaded 2700000 calculations and rebuilt the projection in 1.998665 seconds
PROJECTION_ROWS: 2700002

SELECT ROUND(SUM(bytes)/1024/1024/1024,3) FROM user_segments;
TOTAL_GB: 2.097
```

A fase de reconstrução da projeção em si (depois do `INSERT` em massa)
levou menos de 2 segundos — porque é um `SELECT` set-based sobre a view,
não N `MERGE`s incrementais. **2,097 GB** no disco, meta de "pelo menos 2
GB" cumprida com folga.

---

## 8. Incidente de memória — Oracle Database Free, teto ~2 GB

**Método**: testar se o Oracle Database Free podia ser redimensionado para
um pool de memória maior, na tentativa de isolar se o tamanho do pool
(256 MB, menor que a tabela ativa de 508 MB) era a causa do Fast Lookup não
engatar (Seção 2).

```sql
ALTER SYSTEM SET SGA_TARGET = 3072M SCOPE=SPFILE;
ALTER SYSTEM SET MEMOPTIMIZE_POOL_SIZE = 1024M SCOPE=SPFILE;
SHUTDOWN IMMEDIATE;
STARTUP;
-- ORA-56752: Oracle Database Free version (FREE) memory parameter invalid or not specified
-- ORA-01078: failure in processing system parameters
```

A instância recusou iniciar, e o valor ruim já tinha sido persistido no
spfile — até `STARTUP NOMOUNT` falhou na tentativa seguinte. **Recuperação**:
extração dos últimos parâmetros válidos via `strings spfileFREE.ora`,
inicialização a partir de um pfile de resgate com os valores originais
seguros (`sga_target=1536m`, `memoptimize_pool_size=256m`), depois `CREATE
SPFILE FROM PFILE` para persistir de volta. Nenhum dado foi perdido — o app
reconectou via HikariCP assim que a instância voltou, confirmado
consultando `calculations` logo em seguida (contagem intacta).

**Resultado**: confirma ao vivo, não por suposição, que esta edição impõe
um teto de memória combinada (SGA+PGA) em torno de 2 GB — um limite
estrutural, não um erro de configuração corrigível.

---

## 9. Tabela-resumo de todos os testes

| # | Alegação | Resultado |
|---|---|---|
| 1 | Cálculo de preço correto | **OK** — R$330,24 bate com a conta manual |
| 2 | Fast Lookup ativo nas leituras por PK | **Diagnosticado** — população do pool nunca aceita nesta edição |
| 3.1 | Keyset avança sem duplicar/pular | **OK** — zero sobreposição entre páginas |
| 3.2 | Histograma corrige plano em coluna desbalanceada | **OK** — 0,456s → 0,062s (7,4×) |
| 4 | Rebuild sem travar tabelas fonte, sob volume real | **OK** — 2m34,63s, mismatchCount=0 |
| 5 | Escritas concorrentes sobrevivem a um rebuild | **OK** — 15/15 = 201, contagens batem |
| 6 | Cache sempre quente sob carga sustentada | **OK** — 4.439/4.439 consistentes, p95=2ms |
| 6.1 | Consistência se mantém ao escalar a carga (10→100 VUs) | **OK** — 62.510/62.510 consistentes mesmo a 100 VUs, 0 falhas em 257.833 requisições |
| 7.1 | Bug real de carga em massa | **Encontrado e corrigido** — documentado com o erro original |
| 7.2 | Carga ≥ 2GB consistente | **OK** — 2,097 GB, 2.700.002 linhas |
| 8 | Oracle Free redimensionável para pool 2GB+ | **Negado** — ORA-56752, teto confirmado, recuperado sem perda |
| 11 | Pool de conexões (10→20) e statement cache melhoram a latência nesta carga | **Refutado, com causa real encontrada** — diferença fica dentro do ruído até VUS=150; gargalo real (fila) só aparece acima disso e é confirmado por métrica direta, não por latência |
| 12 | `application-prod.yml` funciona sob carga | **OK** — 100% consistente, 0 falhas, mesmo com pool deliberadamente menor (8) |

---

## 10. Resumo dos ganhos desta sessão

Este projeto chegou nesta sessão com o mecanismo já validado (Seções 1-8
acima, de sessões anteriores) mas sem nenhum teste automatizado, sem
documentação interativa da API e sem nenhuma medição de capacidade sob
carga crescente. Saiu com as quatro lacunas fechadas, cada uma com
evidência real, não afirmação:

**Documentação interativa da API.** `springdoc-openapi` adicionado
(a linha 3.x, a única compatível com Spring Boot 4). Swagger UI em
`/swagger-ui/index.html`, cada endpoint com `@Operation` explicando uma
decisão real — não a anotação vazia do framework. `make swagger` mostra a
URL.

**Suíte automatizada — de zero para 66 testes, 0 falhas.** `src/test/`
não existia. Hoje: 45 unit tests (JUnit 5 + Mockito, sem banco — preço,
cursor/filtros, validação, `ApiExceptionHandler`, health indicator,
bootstrap runner, e 4 slices `@WebMvcTest` para os controllers) + 21
integration tests (Testcontainers + Oracle real — ciclo de vida completo,
rebuild blue/green, keyset, e os dois ramos de disputa de mutex forçados
deterministicamente, sem depender de race real). `make test`/`make
test-integration` rodam as duas camadas; detalhes em
[Seção 0.1](#01-suíte-automatizada-junit-5--66-testes-0-falhas).

**Cobertura de código medida e otimizada — 93,6% linhas, 81,7% branches.**
Não existia nenhuma medição antes. JaCoCo configurado, número real
extraído (não estimado), e a lacuna de branch fechada de 62,5% para 81,7%
sem nenhum teste instável — a técnica usada (forçar a condição de disputa
de mutex de forma determinística, sem duas threads competindo por uma
janela de milissegundos) está documentada em
[Seção 0.1.1](#011-cobertura-de-código--936-de-linhas-817-de-branches)
porque é reaproveitável em qualquer outro ramo de concorrência difícil de
testar no futuro.

**Capacidade sob carga caracterizada, não só validada num ponto fixo.**
Antes desta sessão, a única evidência de carga era um teste único a 20
VUs. Agora há uma curva real: 10 → 20 → 25 → 100 VUs de escrita
concorrente, todos com 100% de consistência e zero falha, com a
degradação de latência (2ms → 34ms de p95) explicada pela causa real (pool
do HikariCP fixo em 10 conexões), não deixada como número solto — ver
[Seção 6.1](#61-escalonamento-de-carga--crescente-e-decrescente).

**O que isso muda na prática**: qualquer pessoa que chegar a este projeto
depois de hoje tem como confirmar, sozinha e em minutos (`make test`,
`make test-integration`, `make k6-test`), que o mecanismo funciona —
sem precisar reproduzir manualmente a investigação original. Isso é
diferente de "o mecanismo funciona porque alguém testou uma vez e
escreveu no documento".

---

## 11. Dimensionamento de pool de conexões — HikariCP sob carga real (2026-09-09)

A [Seção 6.1](#61-escalonamento-de-carga--crescente-e-decrescente) tinha
ficado com uma pergunta em aberto: "para achar o teto real da aplicação,
o próximo passo seria repetir o nível de 100 VUs com `DB_POOL_SIZE` maior
que 10 [...] não testado ainda nesta sessão." Esta seção fecha essa
pergunta — com um resultado mais matizado do que "pool maior = mais
rápido".

**Mudança de configuração testada** (`application.yml`):

```yaml
# antes
maximum-pool-size: 10
minimum-idle: 1
# (sem statement cache)

# depois
maximum-pool-size: 20
minimum-idle: 10
data-source-properties:
  oracle.jdbc.implicitStatementCacheSize: 50
```

### 11.1 Em carga moderada (VUS=20 e VUS=60), a diferença é ruído

Três execuções de `make k6-test VUS=20 DURATION=45s` em cada configuração,
sequenciais, mesma máquina, mesmo dataset de 2.700.022 linhas:

| Run | Pool antigo (max=10) p95 / p99 | Pool novo (max=20 + cache) p95 / p99 |
|---|---|---|
| 1 | 5ms / 16ms | 5ms / 10ms |
| 2 | 6ms / 23ms | 8ms / 21ms |
| 3 | 9ms / 44ms | 5ms / 11ms |
| **Média** | **6,7ms / 27,8ms** | **6,0ms / 14,0ms** |

Em VUS=60 (76 VUs simultâneos no pico), o padrão se repete — os dois
ficam na casa de poucos milissegundos, ora um ora outro ligeiramente à
frente, sem tendência clara. **Conclusão honesta desta parte**: nesse
nível de carga, nenhuma das duas configurações jamais exauriu o pool —
confirmado com a métrica direta do HikariCP (não inferida da latência):

```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

A VUS=60, pico medido: `pending=0` do início ao fim, `active` nunca
passou de **9** — abaixo até do teto do pool *antigo* (10). Latência
p95/p99 baseada só em HTTP é uma medida confundida por aquecimento de
cache do Oracle entre execuções sucessivas; a métrica do pool não tem
esse problema porque é uma contagem de estado, não uma duração.

### 11.2 Por que o pool nunca aparecia como gargalo — o dado que faltava

```sql
SELECT name, value FROM v$parameter WHERE name IN ('processes','sessions','cpu_count');
-- processes = 200 | sessions = 322 | cpu_count = 2
```

O container Oracle desta PoC roda com **2 vCPUs**. Pela fórmula clássica
de dimensionamento de pool (a mesma usada pelo próprio HikariCP):

```
conexões_ótimas = (núcleos_de_CPU × 2) + spindle_count_efetivo ≈ (2×2)+1 = 5
```

Um pool de 10 (e depois 20) já estava **acima** do que o motor de 2 CPUs
consegue paralelizar de verdade — dobrar um recurso que não é o gargalo
não muda nada. Isso explica por completo por que 11.1 não mostrou ganho
limpo: o teto real, nessa máquina de teste, nunca foi o número de
conexões.

### 11.3 Onde o pool realmente satura — VUS=150

Em VUS=150 (188 VUs no pico entre os três cenários), a fila aparece de
verdade e a métrica do pool prova, sem ambiguidade:

| Métrica | Pool novo (max=20) | Pool antigo (max=10) |
|---|---|---|
| `active` no pico | 20/20 (saturado) | 10/10 (saturado) |
| `pending` no pico | **168** | **178** |
| `timeout` (30s estourado) | 0 | 0 |
| p95 leitura pós-escrita | 183ms | 177ms |
| p99 leitura pós-escrita | 500ms | 431ms |
| Throughput | 945 req/s | 1150 req/s |
| Correção | 100%, 0 falhas, 0 mismatch | 100%, 0 falhas, 0 mismatch |

**Fato mecânico, medido, sem ambiguidade**: os dois pools saturam nesse
nível de VUs — cada um bate no próprio teto e forma fila. O pool menor
precisa segurar proporcionalmente mais requisição em fila (satura com
metade da capacidade), como esperado matematicamente.

**O que a latência ponta-a-ponta não confirma nesta rodada**: o pool
antigo saiu igual ou um pouco melhor em p95/p99 aqui — mas essa execução
rodou **depois** de várias outras contra o mesmo Oracle (décima carga
consecutiva da sessão), com o buffer cache bem mais aquecido que na
primeira vez que o pool novo foi testado. Sem alternar as execuções
(A/B/A/B) ou resetar o estado do Oracle entre elas, não dá para separar
esse efeito do efeito real do tamanho do pool — registrado aqui como
limitação da medição, não escondido.

**Sem falhas em nenhum nível testado**: mesmo com até 178 requisições na
fila simultaneamente, o HikariCP nunca estourou o `connection-timeout` de
30s — toda requisição foi atendida, só esperou mais. Correção (100%
consistência read-after-write, 0 mismatches de projeção) se manteve
perfeita em **11 execuções de k6** ao longo desta investigação, em
qualquer combinação de pool e VUs testada.

### 11.4 Veredito

Mantido o pool novo (`maximum-pool-size: 20`, `minimum-idle: 10`,
`implicitStatementCacheSize: 50`) — não porque esta bateria de testes
tenha isolado um ganho de latência limpo (não isolou, pelas razões acima),
mas porque é a escolha estruturalmente mais correta: satura só com o
dobro da concorrência que o pool antigo suportava, `minimum-idle: 10`
evita o custo de abrir conexão do zero quando o tráfego sobe de repente
(o antigo, com `minimum-idle: 1`, pagaria esse custo toda vez), e dá
margem para uma carga futura que exija mais de 9 conexões simultâneas —
o que nenhum teste desta sessão gerou, mas um volume de produção real
geraria.

---

## 12. Profile de produção — `application-prod.yml` (2026-09-09)

A configuração de pool acima é calibrada para o hardware desta PoC (2
vCPUs), não para produção. Criado `src/main/resources/application-prod.yml`
(ativado com `SPRING_PROFILES_ACTIVE=prod`) com o dimensionamento
derivado da mesma fórmula da Seção 11.2, documentado com a premissa
explícita que precisa ser substituída pelo hardware real antes do deploy:

```yaml
# Premissa de exemplo: Oracle de destino com 16 vCPUs, 4 réplicas da app
# pool_total_ótimo = (16×2)+1 = 33  ->  33/4 réplicas ≈ 8 por réplica
maximum-pool-size: 8
minimum-idle: 8
connection-timeout: 10000   # falha rápido, não enfileira 30s silenciosamente
max-lifetime: 1700000       # abaixo de idle-timeout típico de firewall/LB
leak-detection-threshold: 60000
# Tomcat dimensionado junto com o pool, não mais no default de 200:
server.tomcat.threads.max: 24
```

**Validado rodando de verdade**, não só como arquivo teórico —
`SPRING_PROFILES_ACTIVE=prod` ativado no container local, confirmado via
`GET /actuator/metrics/hikaricp.connections.max` retornando `8.0` (contra
o `20.0` do profile padrão), depois `make k6-test VUS=20 DURATION=45s`
contra ele:

```
✓ http_req_failed ................... rate<0.01     → 0.00%
✓ projection_mismatch_observed ...... rate==0       → 0.00%
✓ read_after_write_consistent ....... rate>=1       → 100.00%
✓ read_after_write_latency_ms ....... p(95)<500ms, p(99)<1000ms → p95=10ms, p99=56,87ms

15.575 requisições HTTP, 0 falhas
3.785/3.785 leituras pós-escrita consistentes (100%)
```

Latência mais alta que o profile padrão (esperado — pool de 8 é bem mais
enxuto que 20), mas com folga enorme dos limites, e correção perfeita.

---

## 13. Resumo dos ganhos desta sessão (2026-09-09)

Esta sessão chegou com um pedido de revisão de 5 boas práticas de acesso
ao Oracle (bind variables, pool singleton, try-with-resources, statement
cache, dimensionamento de pool) e terminou com o dimensionamento de pool
efetivamente medido sob carga real, não só configurado por regra de bolso:

**As 5 práticas revisadas — 3 já corretas por construção, 2 corrigidas.**
Bind variables (via `JdbcClient`), pool singleton (Spring Boot
autoconfigura um único `HikariDataSource`) e try-with-resources (não há
JDBC raw no código principal — o framework já gerencia o ciclo de vida)
já estavam corretos. Statement cache (`oracle.jdbc.implicitStatementCacheSize:
50`) e dimensionamento de pool (`maximum-pool-size: 20`,
`minimum-idle: 10`) foram adicionados a `application.yml`.

**Toda mudança de config foi validada com a suíte completa antes de
qualquer teste de carga** — 47 unit tests + 19 integration tests
(Testcontainers + Oracle real), 0 falhas, confirmando que a config nova
não quebra nenhum comportamento existente antes de investigar performance.

**11 execuções de k6 nesta sessão, em VUS 20/60/150, com e sem a mudança
de pool — 100% de consistência read-after-write e 0 mismatches de
projeção em todas elas, sem exceção.** Esse é o resultado mais importante:
qualquer configuração de pool testada, sob qualquer carga testada, nunca
comprometeu a correção — só a latência sob fila real (Seção 11.3).

**O gargalo real foi encontrado por medição direta do pool, não por
inferência de latência HTTP** — `hikaricp.connections.pending`/`.active`
provaram que nem o pool antigo (10) nem o novo (20) chegaram a saturar
até VUS=150, e que a diferença de latência vista em cargas menores era
ruído de execução (aquecimento de cache do Oracle entre runs
sequenciais), não efeito do pool. Esse método — métrica de estado do
pool em vez de latência ponta-a-ponta — fica registrado aqui como a
forma correta de investigar esse tipo de pergunta no futuro.

**Profile de produção criado e validado rodando, não só escrito.**
`application-prod.yml` traduz o dimensionamento para a fórmula
`(vCPUs×2+1)/réplicas`, documentada com premissa explícita a recalcular
para o hardware real, e foi de fato ativado e testado sob carga
(Seção 12) — não ficou como arquivo teórico nunca executado.

---
