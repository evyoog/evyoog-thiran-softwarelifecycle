import { useEffect, useState } from 'react'

/**
 * VYB-0769: one {@code aria-live="polite"} region, mounted once at the shell level —
 * "polite" specifically (AC2), which queues behind whatever the screen reader is
 * already saying (including the user's own typing) instead of interrupting it, unlike
 * {@code aria-live="assertive"}. {@link announce} is how anything in the app posts to
 * it; {@link UndoToast} calling it on mount is what satisfies AC1.
 */
let listener: ((message: string) => void) | null = null

export function announce(message: string) {
  listener?.(message)
}

export function LiveAnnouncer() {
  const [message, setMessage] = useState('')

  useEffect(() => {
    listener = (m: string) => {
      // Force a DOM mutation even for a repeated message, so a screen reader that
      // only announces on change still picks up "the same toast happened again."
      setMessage('')
      requestAnimationFrame(() => setMessage(m))
    }
    return () => { listener = null }
  }, [])

  return (
    <div aria-live="polite" role="status" style={{
      position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)', whiteSpace: 'nowrap',
    }}>
      {message}
    </div>
  )
}
