export interface DiffRow {
  type: 'same' | 'add' | 'del'
  text: string
  leftLine: number | null
  rightLine: number | null
}

const MAX_DIFF_LINES = 600

export function computeLineDiff(beforeText: string, afterText: string): { rows: DiffRow[]; truncated: boolean } {
  const aAll = beforeText.replace(/\r\n/g, '\n').split('\n')
  const bAll = afterText.replace(/\r\n/g, '\n').split('\n')
  const truncated = aAll.length > MAX_DIFF_LINES || bAll.length > MAX_DIFF_LINES
  const a = aAll.slice(0, MAX_DIFF_LINES)
  const b = bAll.slice(0, MAX_DIFF_LINES)

  const m = a.length
  const n = b.length
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0))
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? 1 + dp[i + 1][j + 1] : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }

  const rows: DiffRow[] = []
  let i = 0
  let j = 0
  while (i < m && j < n) {
    if (a[i] === b[j]) {
      rows.push({ type: 'same', text: a[i], leftLine: i + 1, rightLine: j + 1 })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      rows.push({ type: 'del', text: a[i], leftLine: i + 1, rightLine: null })
      i++
    } else {
      rows.push({ type: 'add', text: b[j], leftLine: null, rightLine: j + 1 })
      j++
    }
  }
  while (i < m) rows.push({ type: 'del', text: a[i], leftLine: ++i, rightLine: null })
  while (j < n) rows.push({ type: 'add', text: b[j], leftLine: null, rightLine: ++j })
  return { rows, truncated }
}
