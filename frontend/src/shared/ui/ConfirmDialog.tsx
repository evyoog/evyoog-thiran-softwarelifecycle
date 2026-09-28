import { Modal } from './Modal'

/**
 * VYB-0183: confirms a consequential action, naming exactly what it affects — not a
 * bare "Are you sure?". The confirming button is labelled with the action itself.
 * VYB-0768: rendered through {@link Modal}, so this gets the focus trap and
 * restore-on-close for free rather than reimplementing it.
 */
export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  onConfirm,
  onCancel,
}: {
  title: string
  description: string
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <Modal onClose={onCancel} title={title}>
      <h3>{title}</h3>
      <p style={{ fontSize: 12.5, color: 'var(--tx-2)', lineHeight: 1.5 }}>{description}</p>
      <div className="actions">
        <button className="btn" onClick={onCancel}>Cancel</button>
        <button className="btn pri" onClick={onConfirm}>{confirmLabel}</button>
      </div>
    </Modal>
  )
}
