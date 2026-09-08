# Índice da Documentação

Ponto de entrada para toda a documentação da PoC **Trigger-Maintained
Synchronous Read Projection com Memoptimized Rowstore Fast Lookup**. Cada
documento tem um público e um propósito diferente — comece pelo que
corresponde ao que você precisa agora, não necessariamente na ordem da
tabela.

| Documento | Para quem | O que responde |
|---|---|---|
| [`documento-executivo-poc.md`](documento-executivo-poc.md) | Liderança, stakeholders não-técnicos | Por que isso existe, o que foi comprovado, o que não foi, e qual a recomendação. |
| [`architecture.md`](architecture.md) | Arquitetos, tech leads | As decisões de design e seus trade-offs — por que blue/green, por que compound trigger, por que keyset. |
| [`implementacao.md`](implementacao.md) | Quem vai ler ou mexer no código | O código real de cada peça — migrations, pacotes PL/SQL, classes Java — com o trecho e a razão da decisão. |
| [`testes.md`](testes.md) | QA, quem vai validar ou reproduzir | Cada alegação testada, comando exato, saída real, o que ela prova. |
| [`fluxos-e-ciclo-de-vida.md`](fluxos-e-ciclo-de-vida.md) | Arquitetos, PM/PO, quem vai levar isso adiante | Hipótese e prova, versionamento, fluxo de integração (CI/CD), fluxo de negócio, fluxo geral fim-a-fim, e a arquitetura de cada fase do rollout. |

Se você só tem 5 minutos: leia a Seção 1 (Sumário Executivo) de
`documento-executivo-poc.md`. Se você vai defender isso numa revisão
técnica: tenha `architecture.md` e `testes.md` abertos ao mesmo tempo — um
explica a decisão, o outro prova que ela funciona.

## Menu — Documento Executivo (`documento-executivo-poc.md`)

1. [Sumário Executivo](documento-executivo-poc.md#1-sumário-executivo)
2. [A Dor: o Custo do "Cache" na Aplicação de Price](documento-executivo-poc.md#2-a-dor-o-custo-do-cache-na-aplicação-de-price)
3. [Objetivo da PoC](documento-executivo-poc.md#3-objetivo-da-poc)
4. [Abordagem e Metodologia](documento-executivo-poc.md#4-abordagem-e-metodologia)
5. [Resultados — O Que Foi Comprovado](documento-executivo-poc.md#5-resultados--o-que-foi-comprovado)
6. [O Que Não Pôde Ser Comprovado (Transparência)](documento-executivo-poc.md#6-o-que-não-pôde-ser-comprovado-transparência)
7. [Riscos e Trade-offs](documento-executivo-poc.md#7-riscos-e-trade-offs)
8. [Recomendação e Próximos Passos](documento-executivo-poc.md#8-recomendação-e-próximos-passos)
9. [Visão de Implementação e Cronograma](documento-executivo-poc.md#9-visão-de-implementação-e-cronograma)
10. [Apêndice — Referências](documento-executivo-poc.md#10-apêndice--referências)

## Menu — Arquitetura (`architecture.md`)

- [Domínio: preço por cliente com tarifas](architecture.md#domínio-preço-por-cliente-com-tarifas)
- [Fast Lookup não é o In-Memory Column Store](architecture.md#fast-lookup-não-é-o-in-memory-column-store)
- [Forma de acesso elegível](architecture.md#forma-de-acesso-elegível)
- [Consistência](architecture.md#consistência)
- [Bootstrap e rebuild: blue/green por synonym](architecture.md#bootstrap-e-rebuild-bluegreen-por-synonym)
- [Carga em massa](architecture.md#carga-em-massa-oraclebenchmark01_load_datasql)
- [Observabilidade](architecture.md#observabilidade)
- [Limites da prova](architecture.md#limites-da-prova)

## Menu — Implementação (`implementacao.md`)

1. [Banco de dados — migrations Flyway](implementacao.md#1-banco-de-dados--migrations-flyway)
   - [V1 — schema fonte e projeção](implementacao.md#v1--schema-fonte-e-projeção-v1__create_source_and_projectionsql)
   - [V2 — manutenção da projeção](implementacao.md#v2--manutenção-da-projeção-v2__create_projection_maintenancesql)
   - [V3 — seed de demonstração](implementacao.md#v3--seed-de-demonstração-v3__seed_demonstration_datasql)
2. [Camada de aplicação — Java](implementacao.md#2-camada-de-aplicação--java-spring-boot-41-java-21)
   - [Diagrama de classe](implementacao.md#21-diagrama-de-classe)
   - [`PriceResolutionService`](implementacao.md#priceresolutionservice--o-cálculo-de-preço-em-si)
   - [`CalculationCommandService`](implementacao.md#calculationcommandservice--escrita-transacional)
   - [`CalculationReadRepository`](implementacao.md#calculationreadrepository--leitura-por-pk-e-busca-filtrada)
   - [`ProjectionService`](implementacao.md#projectionservice--o-mutex-e-o-rebuild-bluegreen)
   - [Outros componentes de suporte](implementacao.md#outros-componentes-de-suporte)
   - [API REST](implementacao.md#api-rest-api)
3. [Carga em massa](implementacao.md#3-carga-em-massa-oraclebenchmark01_load_datasql)
4. [Ferramentas de verificação](implementacao.md#4-ferramentas-de-verificação)

## Menu — Testes (`testes.md`)

- [0. Como rodar](testes.md#0-como-rodar) ([suíte automatizada, 66 testes](testes.md#01-suíte-automatizada-junit-5--66-testes-0-falhas), [cobertura de código — 93,6%/81,7%](testes.md#011-cobertura-de-código--936-de-linhas-817-de-branches), [verificação end-to-end 2026-09-06](testes.md#02-execução-de-verificação-end-to-end--2026-09-06))
- [1. Correção do cálculo de preço](testes.md#1-correção-do-cálculo-de-preço)
- [2. Fast Lookup — plano de execução e diagnóstico completo](testes.md#2-fast-lookup--plano-de-execução-real-e-diagnóstico-completo)
- [3. Busca filtrada](testes.md#3-busca-filtrada) ([keyset](testes.md#31-paginação-por-keyset--sem-duplicar-nem-pular-linhas), [histograma](testes.md#32-coluna-desbalanceada-precisa-de-histograma-não-só-de-índice))
- [4. Rebuild blue/green sob volume real](testes.md#4-rebuild-bluegreen-sob-volume-real)
- [5. Concorrência real durante um rebuild](testes.md#5-concorrência-real-durante-um-rebuild--o-teste-mais-importante)
- [6. Carga sustentada — k6](testes.md#6-carga-sustentada--k6) ([6.1 escalonamento 10→100 VUs](testes.md#61-escalonamento-de-carga--crescente-e-decrescente))
- [7. Carga em massa — o bug real e a carga final](testes.md#7-carga-em-massa--o-bug-real-encontrado-e-a-carga-final)
- [8. Incidente de memória — Oracle Free, teto ~2 GB](testes.md#8-incidente-de-memória--oracle-database-free-teto-2-gb)
- [9. Tabela-resumo de todos os testes](testes.md#9-tabela-resumo-de-todos-os-testes)
- [10. Resumo dos ganhos desta sessão](testes.md#10-resumo-dos-ganhos-desta-sessão)

## Menu — Ciclo de Vida e Fluxos (`fluxos-e-ciclo-de-vida.md`)

- [0. Diagnóstico — o que estava faltando](fluxos-e-ciclo-de-vida.md#0-diagnóstico--o-que-estava-faltando)
- [1. Hipótese e Prova](fluxos-e-ciclo-de-vida.md#1-hipótese-e-prova)
- [2. Versionamento](fluxos-e-ciclo-de-vida.md#2-versionamento) ([schema](fluxos-e-ciclo-de-vida.md#21-versão-do-schema-da-projeção-projection_controlschema_version), [migrations](fluxos-e-ciclo-de-vida.md#22-versão-das-migrations-flyway-v1-v2-v3), [app/API](fluxos-e-ciclo-de-vida.md#23-versão-da-aplicação-e-da-api--gap-identificado-ainda-não-resolvido))
- [3. Fluxo de Integração (CI/CD)](fluxos-e-ciclo-de-vida.md#3-fluxo-de-integração-cicd)
- [4. Fluxo de Negócio](fluxos-e-ciclo-de-vida.md#4-fluxo-de-negócio)
- [5. Fluxo Geral (fim a fim)](fluxos-e-ciclo-de-vida.md#5-fluxo-geral-fim-a-fim)
- [6. Roadmap de Arquitetura por Fase](fluxos-e-ciclo-de-vida.md#6-roadmap-de-arquitetura-por-fase)

## Fora de `docs/`

- [`../README.md`](../README.md) — quickstart do projeto: subir o ambiente, credenciais, comandos `curl` de verificação, benchmark.
- [`../Makefile`](../Makefile) — `make help` lista todo comando disponível (ambiente, qualidade, administração da projeção, dados/carga, k6).
- [`../postman/`](../postman/oracle-read-projection-poc.postman_collection.json) — coleção Postman com todos os endpoints.
- [`../k6/read-after-write-test.js`](../k6/read-after-write-test.js) — suíte de carga, detalhada em `testes.md`.
