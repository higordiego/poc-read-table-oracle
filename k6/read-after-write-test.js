// k6 run k6/read-after-write-test.js
//
// Valida a promessa central deste PoC sob carga: a projeção de leitura
// ("cache") não fica "eventualmente" consistente -- ela tem que estar
// correta no instante seguinte ao commit da escrita, porque o trigger que
// mantém a projeção roda dentro da mesma transação. Este teste não mede
// "quanto tempo até o cache esquentar" (não deveria haver esse tempo); ele
// prova que não existe janela de inconsistência, mesmo sob VUs concorrentes.
//
// Três cenários rodam em paralelo:
//   1. write_and_verify -- cria um cálculo, atualiza o status, e em cada
//      caso lê de volta IMEDIATAMENTE (sem sleep, sem retry) conferindo que
//      o valor lido bate exatamente com o que a escrita acabou de produzir.
//   2. search_load -- busca filtrada (COUNT + keyset) concorrente, para
//      garantir que o caminho de busca não degrada nem quebra sob carga.
//   3. status_watch -- observa /api/admin/projection/status periodicamente
//      durante o teste inteiro; mismatchCount tem que continuar 0 do início
//      ao fim.
//
// Uso:
//   BASE_URL=http://localhost:8080 VUS=15 DURATION=60s k6 run k6/read-after-write-test.js
//   make k6-test VUS=15 DURATION=60s

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 10);
const DURATION = __ENV.DURATION || '60s';
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// --- Métricas customizadas: o ponto inteiro deste teste. ---------------
const readAfterWriteConsistent = new Rate('read_after_write_consistent');
const readAfterWriteLatencyMs = new Trend('read_after_write_latency_ms', true);
const statusUpdateConsistent = new Rate('status_update_consistent');
const searchOk = new Rate('search_ok');
const projectionMismatch = new Rate('projection_mismatch_observed');

export const options = {
  scenarios: {
    write_and_verify: {
      executor: 'ramping-vus',
      exec: 'writeAndVerify',
      startVUs: 0,
      stages: [
        { duration: '15s', target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '10s', target: 0 },
      ],
      gracefulStop: '15s',
    },
    search_load: {
      executor: 'constant-vus',
      exec: 'searchLoad',
      vus: Math.max(2, Math.floor(VUS / 4)),
      duration: DURATION,
      startTime: '15s',
    },
    status_watch: {
      executor: 'constant-vus',
      exec: 'statusWatch',
      vus: 1,
      duration: DURATION,
      startTime: '15s',
    },
  },
  thresholds: {
    // A alegação central: NUNCA pode haver uma leitura pós-escrita
    // inconsistente. Qualquer falha aqui reprova o teste inteiro.
    read_after_write_consistent: ['rate>=1'],
    status_update_consistent: ['rate>=1'],
    projection_mismatch_observed: ['rate==0'],
    search_ok: ['rate>=0.99'],
    http_req_failed: ['rate<0.01'],
    'read_after_write_latency_ms': ['p(95)<500', 'p(99)<1000'],
  },
};

function randInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function randomItem(arr) {
  return arr[randInt(0, arr.length - 1)];
}

// --- setup: roda uma vez, cria o cliente usado pelo teste todo. --------
export function setup() {
  const res = http.post(
    `${BASE_URL}/api/customers`,
    JSON.stringify({
      segmentId: 1,
      name: 'k6 Load Test',
      email: `k6-loadtest-${Date.now()}@example.com`,
      status: 'ACTIVE',
    }),
    JSON_HEADERS
  );
  if (res.status !== 201) {
    throw new Error(`setup: falha ao criar cliente de teste (${res.status}): ${res.body}`);
  }
  const customerId = res.json('id');
  console.log(`[setup] customerId=${customerId} baseUrl=${BASE_URL} vus=${VUS} duration=${DURATION}`);
  return { customerId };
}

// --- cenário 1: escreve, lê de volta na hora, confere. ------------------
export function writeAndVerify(data) {
  const quantity = randInt(1, 80);
  const payload = JSON.stringify({
    customerId: data.customerId,
    priceTableId: 1,
    items: [{ priceTableItemId: 1, quantity }],
  });

  const writeRes = http.post(`${BASE_URL}/api/calculations`, payload, {
    ...JSON_HEADERS,
    tags: { name: 'create_calculation' },
  });

  const created = check(writeRes, { 'create: 201': (r) => r.status === 201 });
  if (!created) {
    return;
  }

  const writeBody = writeRes.json();
  const calcId = writeBody.calculationId;
  const expectedTotal = writeBody.totalAmount;

  // Sem sleep, sem retry: é exatamente isso que estamos testando -- a
  // projeção já tem que refletir a escrita no request seguinte.
  const t0 = Date.now();
  const readRes = http.get(`${BASE_URL}/api/read/calculations/${calcId}?mode=projection`, {
    tags: { name: 'read_after_write' },
  });
  readAfterWriteLatencyMs.add(Date.now() - t0);

  const consistent = check(readRes, {
    'read-after-write: 200': (r) => r.status === 200,
    'read-after-write: totalAmount bate': (r) => {
      if (r.status !== 200) return false;
      const readTotal = r.json('totalAmount');
      return Math.abs(readTotal - expectedTotal) < 0.01;
    },
    'read-after-write: lineItemCount bate': (r) => r.status === 200 && r.json('lineItemCount') === 1,
  });
  readAfterWriteConsistent.add(consistent);

  // Segunda escrita sobre o mesmo cálculo (update de status) + leitura
  // imediata de novo -- confere que a projeção também acompanha updates,
  // não só inserts.
  const newStatus = randomItem(['CONFIRMED', 'CANCELLED']);
  const patchRes = http.patch(
    `${BASE_URL}/api/calculations/${calcId}/status`,
    JSON.stringify({ status: newStatus }),
    { ...JSON_HEADERS, tags: { name: 'update_status' } }
  );
  const patched = check(patchRes, { 'update status: 200': (r) => r.status === 200 });
  if (!patched) {
    return;
  }

  const readAfterPatch = http.get(`${BASE_URL}/api/read/calculations/${calcId}?mode=projection`, {
    tags: { name: 'read_after_patch' },
  });
  const patchConsistent = check(readAfterPatch, {
    'read-after-patch: 200': (r) => r.status === 200,
    'read-after-patch: status atualizado': (r) => r.status === 200 && r.json('status') === newStatus,
  });
  statusUpdateConsistent.add(patchConsistent);

  sleep(randInt(1, 4) / 10);
}

// --- cenário 2: busca filtrada concorrente. ------------------------------
export function searchLoad(data) {
  const limit = randInt(5, 30);
  const res = http.get(
    `${BASE_URL}/api/read/calculations?customerId=${data.customerId}&limit=${limit}`,
    { tags: { name: 'search' } }
  );
  const ok = check(res, {
    'search: 200': (r) => r.status === 200,
    'search: total presente': (r) => r.status === 200 && typeof r.json('total') === 'number',
    'search: results presente': (r) => r.status === 200 && Array.isArray(r.json('results')),
  });
  searchOk.add(ok);
  sleep(0.5);
}

// --- cenário 3: observa mismatchCount continuamente durante a carga. ----
export function statusWatch() {
  const res = http.get(`${BASE_URL}/api/admin/projection/status`, { tags: { name: 'status_watch' } });
  const ok = check(res, { 'status: 200': (r) => r.status === 200 });
  if (ok) {
    const mismatch = res.json('mismatchCount');
    projectionMismatch.add(mismatch !== 0);
    if (mismatch !== 0) {
      console.error(`[status_watch] mismatchCount=${mismatch} em ${new Date().toISOString()}`);
    }
  }
  sleep(2);
}

export function teardown(data) {
  console.log(`[teardown] concluído para customerId=${data.customerId}`);
}
