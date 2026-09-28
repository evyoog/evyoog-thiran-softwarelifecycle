import { useEffect, useRef, useState } from 'react'

/**
 * VYB-0767: the roving-tabindex + arrow-key pattern `Requirements.tsx` already had,
 * extracted so every other `.tbl` in this codebase (previously plain click-only HTML
 * tables — CoverageMatrixPage, Analytics, Quality, Admin, Releases) can pick up the
 * same real keyboard operability with a few lines each, not a second bespoke
 * implementation per table.
 */
export function useRovingGrid(rowCount: number, colCount: number, onActivate?: (row: number, col: number) => void) {
  const [focused, setFocused] = useState({ row: 0, col: 0 })
  const cellRefs = useRef<(HTMLElement | null)[][]>([])

  useEffect(() => {
    cellRefs.current[focused.row]?.[focused.col]?.focus()
  }, [focused])

  useEffect(() => {
    // A reloaded/refiltered table starts focus back at the first cell, same as
    // Requirements.tsx's own grid does on new data.
    setFocused({ row: 0, col: 0 })
  }, [rowCount])

  const cellRef = (row: number, col: number) => (el: HTMLElement | null) => {
    if (!cellRefs.current[row]) cellRefs.current[row] = []
    cellRefs.current[row][col] = el
  }

  const cellProps = (row: number, col: number, extraStyle?: React.CSSProperties) => ({
    ref: cellRef(row, col),
    tabIndex: focused.row === row && focused.col === col ? 0 : -1,
    role: 'gridcell' as const,
    onFocus: () => setFocused({ row, col }),
    style: {
      ...extraStyle,
      ...(focused.row === row && focused.col === col ? { outline: '2px solid var(--brand)', outlineOffset: -2 } : {}),
    },
  })

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (rowCount === 0) return
    const maxRow = rowCount - 1
    const maxCol = colCount - 1
    if (e.key === 'ArrowDown') { e.preventDefault(); setFocused((c) => ({ ...c, row: Math.min(c.row + 1, maxRow) })) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setFocused((c) => ({ ...c, row: Math.max(c.row - 1, 0) })) }
    else if (e.key === 'ArrowRight') { e.preventDefault(); setFocused((c) => ({ ...c, col: Math.min(c.col + 1, maxCol) })) }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); setFocused((c) => ({ ...c, col: Math.max(c.col - 1, 0) })) }
    else if (e.key === 'Home') { e.preventDefault(); setFocused((c) => ({ ...c, row: 0 })) }
    else if (e.key === 'End') { e.preventDefault(); setFocused((c) => ({ ...c, row: maxRow })) }
    else if (e.key === 'Enter') { onActivate?.(focused.row, focused.col) }
  }

  return { focused, setFocused, cellProps, onKeyDown }
}
