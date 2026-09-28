import { useEffect, useRef } from 'react'

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * VYB-0768: every modal in the app should render through this, not a bare
 * {@code .modal-scrim}/{@code .modal} pair — it's what actually traps Tab inside the
 * dialog (AC1) and restores focus to whatever opened it when Escape closes it (AC2),
 * rather than each dialog reimplementing that itself. Retrofitted onto every existing
 * modal this session touched; any left as a raw div pair predates this and is a real,
 * disclosed gap (see BUILD-REGISTER.md) rather than something this component fixes by
 * merely existing.
 */
export function Modal({
  title, onClose, children, labelledBy, maxWidth,
}: { title?: string; onClose: () => void; children: React.ReactNode; labelledBy?: string; maxWidth?: number }) {
  const modalRef = useRef<HTMLDivElement>(null)
  const previouslyFocused = useRef<Element | null>(null)

  useEffect(() => {
    previouslyFocused.current = document.activeElement
    const first = modalRef.current?.querySelector<HTMLElement>(FOCUSABLE)
    first?.focus()
    return () => {
      // VYB-0768 AC2: focus goes back to whatever had it before this opened.
      if (previouslyFocused.current instanceof HTMLElement) previouslyFocused.current.focus()
    }
  }, [])

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
        return
      }
      if (e.key !== 'Tab' || !modalRef.current) return
      const focusable = Array.from(modalRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      // VYB-0768 AC1: Tab cannot leave the dialog — wraps at both ends instead.
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown, true)
    return () => document.removeEventListener('keydown', onKeyDown, true)
  }, [onClose])

  return (
    <div className="modal-scrim" onClick={onClose}>
      <div
        ref={modalRef} className="modal" role="dialog" aria-modal="true"
        aria-label={labelledBy ? undefined : title} aria-labelledby={labelledBy}
        style={maxWidth ? { maxWidth } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  )
}
