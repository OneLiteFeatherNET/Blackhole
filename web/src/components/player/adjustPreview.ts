export type AdjustOutcome = 'none' | 'soft' | 'permanent'

export interface AdjustPreview {
  scoreAfter: number
  /** The automatic punishment the backend would apply because of the change. */
  outcome: AdjustOutcome
}

/**
 * What a manual score change does, mirroring the backend's default escalation policy: while a
 * permanent punishment is in force or when the score rises nothing is decided; a score below the
 * permanent threshold punishes permanently; a downward crossing of the soft threshold temporarily.
 */
export function previewAdjustment(
  score: number,
  delta: number,
  softThreshold: number | null | undefined,
  permanentThreshold: number,
  hasActivePermanent: boolean,
): AdjustPreview {
  const scoreAfter = score + delta
  if (delta >= 0 || hasActivePermanent) return { scoreAfter, outcome: 'none' }
  if (scoreAfter < permanentThreshold) return { scoreAfter, outcome: 'permanent' }
  if (softThreshold !== null && softThreshold !== undefined && score >= softThreshold && scoreAfter < softThreshold) {
    return { scoreAfter, outcome: 'soft' }
  }
  return { scoreAfter, outcome: 'none' }
}
