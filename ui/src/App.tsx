import React, { useEffect, useMemo, useState } from 'react'
import { createRun, getRun, getSources, listRuns, renderPipeline, startRun, type Source } from './api'

type PipelineDraft = {
  name: string
  seed: number
  dataDir: string
  tier2: boolean
  sources: string[]
  transforms: {
    tier1: { languages: string[]; scope: 'all' | 'high-affinity'; batchSize: number }
    tier2: { languages: string[]; scope: 'all' | 'high-affinity'; batchSize: number }
  }
}

const DEFAULT_TIER1 = ['es', 'fr', 'zh', 'ar', 'ja', 'hi', 'ru', 'pt', 'de', 'ko']
const DEFAULT_TIER2 = ['tl', 'sw', 'ur', 'bn', 'th', 'vi', 'id', 'tr', 'fa', 'he']

function parseLangs(s: string): string[] {
  return s
    .split(/[\s,]+/)
    .map((x) => x.trim())
    .filter(Boolean)
    .map((x) => x.replace(/^:/, ''))
}

export function App() {
  const [sources, setSources] = useState<Source[]>([])
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<Record<string, boolean>>({})
  const [edn, setEdn] = useState<string>('')
  const [runs, setRuns] = useState<any[]>([])
  const [activeRunId, setActiveRunId] = useState<string | null>(null)
  const [activeRun, setActiveRun] = useState<any | null>(null)
  const [logTail, setLogTail] = useState<string>('')
  const [err, setErr] = useState<string>('')

  const [name, setName] = useState('dataset')
  const [seed, setSeed] = useState(1337)
  const [tier2Enabled, setTier2Enabled] = useState(true)
  const [tier1Scope, setTier1Scope] = useState<'all' | 'high-affinity'>('all')
  const [tier2Scope, setTier2Scope] = useState<'all' | 'high-affinity'>('all')
  const [tier1Langs, setTier1Langs] = useState(DEFAULT_TIER1.join(', '))
  const [tier2Langs, setTier2Langs] = useState(DEFAULT_TIER2.join(', '))
  const [batchSize, setBatchSize] = useState(25)

  const draft: PipelineDraft = useMemo(
    () => ({
      name,
      seed,
      dataDir: 'data',
      tier2: tier2Enabled,
      sources: Object.entries(selected)
        .filter(([, v]) => v)
        .map(([k]) => k),
      transforms: {
        tier1: {
          languages: parseLangs(tier1Langs),
          scope: tier1Scope,
          batchSize,
        },
        tier2: {
          languages: parseLangs(tier2Langs),
          scope: tier2Scope,
          batchSize,
        },
      },
    }),
    [selected, name, seed, tier2Enabled, tier1Scope, tier2Scope, tier1Langs, tier2Langs, batchSize],
  )

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return sources
    return sources.filter((s) =>
      `${s.name} ${s.description} ${s.license ?? ''} ${s.format ?? ''}`.toLowerCase().includes(q),
    )
  }, [sources, query])

  async function refreshRuns() {
    const r = await listRuns()
    setRuns(r.runs ?? [])
  }

  useEffect(() => {
    ;(async () => {
      try {
        setErr('')
        const s = await getSources()
        setSources(s)
        await refreshRuns()
      } catch (e: any) {
        setErr(e?.message ?? String(e))
      }
    })()
  }, [])

  useEffect(() => {
    if (!activeRunId) return
    const t = setInterval(async () => {
      try {
        const r = await getRun(activeRunId)
        setActiveRun(r.run)
        setLogTail(r.logTail ?? '')
      } catch {
        // ignore polling errors
      }
    }, 1500)
    return () => clearInterval(t)
  }, [activeRunId])

  return (
    <div className="layout">
      <header>
        <h1>promptbench</h1>
        <div className="sub">UI state → EDN grammar instance → run</div>
      </header>

      {err ? <div className="error">{err}</div> : null}

      <div className="grid">
        <section>
          <h2>Sources (def-source)</h2>
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="filter…" />
          <div className="list">
            {filtered.map((s) => (
              <label key={s.name} className="row">
                <input
                  type="checkbox"
                  checked={Boolean(selected[s.name])}
                  onChange={(e) => setSelected((prev) => ({ ...prev, [s.name]: e.target.checked }))}
                />
                <span className="name">{s.name}</span>
                <span className="meta">
                  {s.format ?? '—'} / {s.license ?? '—'}
                </span>
              </label>
            ))}
          </div>
        </section>

        <section>
          <h2>Pipeline instance (def-pipeline)</h2>
          <div className="form">
            <label>
              <span>name</span>
              <input value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <label>
              <span>seed</span>
              <input
                type="number"
                value={seed}
                onChange={(e) => setSeed(Number.parseInt(e.target.value || '1337', 10))}
              />
            </label>
            <label className="inline">
              <input type="checkbox" checked={tier2Enabled} onChange={(e) => setTier2Enabled(e.target.checked)} />
              <span>enable tier2</span>
            </label>
            <label>
              <span>mt batch size</span>
              <input
                type="number"
                value={batchSize}
                onChange={(e) => setBatchSize(Number.parseInt(e.target.value || '25', 10))}
              />
            </label>
            <label>
              <span>tier1 languages</span>
              <input value={tier1Langs} onChange={(e) => setTier1Langs(e.target.value)} />
            </label>
            <label>
              <span>tier1 scope</span>
              <select value={tier1Scope} onChange={(e) => setTier1Scope(e.target.value as any)}>
                <option value="all">all</option>
                <option value="high-affinity">high-affinity</option>
              </select>
            </label>
            <label>
              <span>tier2 languages</span>
              <input value={tier2Langs} onChange={(e) => setTier2Langs(e.target.value)} />
            </label>
            <label>
              <span>tier2 scope</span>
              <select value={tier2Scope} onChange={(e) => setTier2Scope(e.target.value as any)}>
                <option value="all">all</option>
                <option value="high-affinity">high-affinity</option>
              </select>
            </label>
          </div>
          <div className="buttons">
            <button
              onClick={async () => {
                setErr('')
                const r = await renderPipeline(draft)
                setEdn(r.edn)
              }}
            >
              Render EDN
            </button>
            <button
              onClick={async () => {
                setErr('')
                const r = await createRun(draft)
                setEdn(r.run?.config ? '' : edn)
                setActiveRunId(r.run.id)
                await refreshRuns()
              }}
            >
              Create run
            </button>
          </div>
          <pre className="edn">{edn || 'Render EDN to preview the grammar instance.'}</pre>
        </section>

        <section>
          <h2>Runs</h2>
          <div className="buttons">
            <button onClick={refreshRuns}>Refresh</button>
          </div>
          <div className="list">
            {runs.map((r) => (
              <button
                key={r.id}
                className={activeRunId === r.id ? 'run active' : 'run'}
                onClick={() => setActiveRunId(r.id)}
              >
                <div className="runTitle">{r.id}</div>
                <div className="runMeta">{r.status}</div>
              </button>
            ))}
          </div>
        </section>

        <section>
          <h2>Run control</h2>
          <div className="buttons">
            <button disabled={!activeRunId} onClick={() => activeRunId && startRun(activeRunId, 'fetch')}>
              Fetch
            </button>
            <button disabled={!activeRunId} onClick={() => activeRunId && startRun(activeRunId, 'build')}>
              Build
            </button>
            <button disabled={!activeRunId} onClick={() => activeRunId && startRun(activeRunId, 'verify')}>
              Verify
            </button>
          </div>
          <div className="kv">
            <div>active:</div>
            <div>{activeRunId ?? '—'}</div>
            <div>status:</div>
            <div>{activeRun?.status ?? '—'}</div>
          </div>
          <pre className="log">{logTail || '—'}</pre>
        </section>
      </div>
    </div>
  )
}
