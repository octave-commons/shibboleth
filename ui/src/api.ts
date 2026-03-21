export type Source = {
  name: string
  description: string
  version: string
  license: string | null
  format: string | null
  url?: string
  urls?: string[]
  path?: string
}

export type Run = Record<string, unknown>
export type BenchmarkRun = Record<string, unknown>

async function fetchJson(path: string, init?: RequestInit): Promise<any> {
  const res = await fetch(path, init)
  if (!res.ok) throw new Error(`${path}: ${res.status}`)
  return res.json()
}

export async function getSources(): Promise<Source[]> {
  const json = await fetchJson('/api/sources')
  return json.sources as Source[]
}

export async function renderPipeline(payload: unknown): Promise<{ edn: string }> {
  return fetchJson('/api/render/pipeline', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export async function createRun(payload: unknown): Promise<any> {
  return fetchJson('/api/runs', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export async function listRuns(): Promise<any> {
  return fetchJson('/api/runs')
}

export async function getRun(id: string): Promise<any> {
  return fetchJson(`/api/runs/${encodeURIComponent(id)}`)
}

export async function startRun(id: string, command: string): Promise<any> {
  return fetchJson(`/api/runs/${encodeURIComponent(id)}/start`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ command }),
  })
}

export async function listBenchRuns(): Promise<any> {
  return fetchJson('/api/bench/runs')
}

export async function getBenchRun(id: string): Promise<any> {
  return fetchJson(`/api/bench/runs/${encodeURIComponent(id)}`)
}

export async function getBenchAggregate(payload?: unknown): Promise<any> {
  if (payload === undefined) {
    return fetchJson('/api/bench/aggregate')
  }
  return fetchJson('/api/bench/aggregate', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export async function startBenchRun(payload: unknown): Promise<any> {
  return fetchJson('/api/bench/runs', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export async function stopBenchRun(id: string): Promise<any> {
  return fetchJson(`/api/bench/runs/${encodeURIComponent(id)}/stop`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({}),
  })
}
