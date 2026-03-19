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

// Runs are returned from Clojure as JSON maps with kebab-case keys.
// We treat them as opaque objects in the UI for now.
export type Run = Record<string, unknown>

export async function getSources(): Promise<Source[]> {
  const res = await fetch('/api/sources')
  if (!res.ok) throw new Error(`sources: ${res.status}`)
  const json = await res.json()
  return json.sources as Source[]
}

export async function renderPipeline(payload: unknown): Promise<{ edn: string }> {
  const res = await fetch('/api/render/pipeline', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!res.ok) throw new Error(`render: ${res.status}`)
  return res.json()
}

export async function createRun(payload: unknown): Promise<any> {
  const res = await fetch('/api/runs', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!res.ok) throw new Error(`createRun: ${res.status}`)
  return res.json()
}

export async function listRuns(): Promise<any> {
  const res = await fetch('/api/runs')
  if (!res.ok) throw new Error(`runs: ${res.status}`)
  return res.json()
}

export async function getRun(id: string): Promise<any> {
  const res = await fetch(`/api/runs/${encodeURIComponent(id)}`)
  if (!res.ok) throw new Error(`run: ${res.status}`)
  return res.json()
}

export async function startRun(id: string, command: string): Promise<any> {
  const res = await fetch(`/api/runs/${encodeURIComponent(id)}/start`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ command }),
  })
  if (!res.ok) throw new Error(`start: ${res.status}`)
  return res.json()
}
