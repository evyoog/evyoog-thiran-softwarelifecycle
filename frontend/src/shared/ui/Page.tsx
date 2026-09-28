import type { ReactNode } from 'react'

export function Page({
  eyebrow,
  title,
  desc,
  actions,
  children,
}: {
  eyebrow?: string
  title: string
  desc?: string
  actions?: ReactNode
  children?: ReactNode
}) {
  return (
    <>
      <div className="ph" style={{ display: 'flex', alignItems: 'flex-start', gap: 16 }}>
        <div style={{ minWidth: 0 }}>
          {eyebrow && <div className="eyebrow">{eyebrow}</div>}
          <h1 className="ph-t">{title}</h1>
          {desc && <p className="ph-d">{desc}</p>}
        </div>
        <div className="sp" />
        {actions}
      </div>
      {children}
    </>
  )
}

export function Empty({ title, desc }: { title: string; desc: string }) {
  return (
    <div className="empty">
      <h4>{title}</h4>
      <p>{desc}</p>
    </div>
  )
}
