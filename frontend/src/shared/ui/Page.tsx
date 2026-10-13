import { useEffect, useRef, type ReactNode } from 'react'

/** Set once any screen has rendered, so only a move to another screen (not the first load) takes focus. */
let navigated = false

/**
 * A screen's header. VYB-0958: the title is the page's one level-one heading and also the browser tab's title (WCAG 2.4.2), and
 * when the person moves to another screen focus moves to that heading, so a screen reader announces where they have arrived
 * instead of staying on the menu item they activated. The first load does not take focus.
 */
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
  const heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    document.title = `${title} · Vyoog`
  }, [title])

  useEffect(() => {
    if (navigated) heading.current?.focus({ preventScroll: true })
    navigated = true
  }, [])

  return (
    <>
      <div className="ph" style={{ display: 'flex', alignItems: 'flex-start', gap: 16 }}>
        <div style={{ minWidth: 0 }}>
          {eyebrow && <div className="eyebrow">{eyebrow}</div>}
          <h1 className="ph-t" ref={heading} tabIndex={-1} style={{ outline: 'none' }}>{title}</h1>
          {desc && <p className="ph-d">{desc}</p>}
        </div>
        <div className="sp" />
        {actions}
      </div>
      {children}
    </>
  )
}

export function Empty({
  title,
  desc,
}: {
  title: string
  desc: string
}) {
  return (
    <div className="empty">
      <h2>{title}</h2>
      <p>{desc}</p>
    </div>
  )
}
