import { useEffect, useState } from 'react'
import { announce } from './Announcer'

/**
 * VYB-0182: at least seven seconds to undo a reversible mutation. AC2 — the timeout
 * dismissing this on its own never calls {@code onUndo}; only clicking the button
 * does. AC3 — a failed undo says so, in place, rather than just vanishing.
 */
export function UndoToast({
  message,
  onUndo,
  durationMs = 7000,
  onExpire,
}: {
  message: string
  onUndo: () => Promise<void>
  durationMs?: number
  onExpire: () => void
}) {
  const [state, setState] = useState<'idle' | 'undoing' | 'failed'>('idle')

  // VYB-0769 AC1: a sighted user sees this appear; a screen-reader user needs the
  // same fact spoken, since nothing about a toast popping in is otherwise announced.
  useEffect(() => { announce(message) }, [message])

  useEffect(() => {
    if (state !== 'idle') return
    const handle = setTimeout(onExpire, durationMs)
    return () => clearTimeout(handle)
  }, [state, durationMs, onExpire])

  return (
    <div
      style={{
        position: 'fixed', bottom: 20, left: '50%', transform: 'translateX(-50%)', zIndex: 90,
        background: 'var(--panel-3)', border: '1px solid var(--line-2)', borderRadius: 10,
        boxShadow: 'var(--shadow)', padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 12,
        fontSize: 12.5,
      }}
    >
      <span>{state === 'failed' ? 'Could not undo — the rows may have changed since.' : message}</span>
      {state !== 'failed' && (
        <button
          className="btn pri"
          disabled={state === 'undoing'}
          onClick={async () => {
            setState('undoing')
            try {
              await onUndo()
              onExpire()
            } catch {
              setState('failed')
              setTimeout(onExpire, 4000)
            }
          }}
        >
          {state === 'undoing' ? 'Undoing…' : 'Undo'}
        </button>
      )}
      <button className="btn" style={{ padding: 4 }} onClick={onExpire}>✕</button>
    </div>
  )
}
