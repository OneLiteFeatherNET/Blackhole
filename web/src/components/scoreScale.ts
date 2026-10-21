export type ScoreZone = 'healthy' | 'soft' | 'permanent'

/**
 * Where a score stands against a section's thresholds. Mirrors the backend rule: a score *below*
 * the permanent threshold causes a permanent punishment, below the soft threshold a temporary one.
 */
export function scoreZone(score: number, softThreshold: number | null | undefined, permanentThreshold: number): ScoreZone {
  if (score < permanentThreshold) return 'permanent'
  if (softThreshold !== null && softThreshold !== undefined && score < softThreshold) return 'soft'
  return 'healthy'
}

export interface Scale {
  min: number
  max: number
  /** Position of a value on the bar in percent, clamped to 0-100. */
  percent: (value: number) => number
}

/** A bar scale from zero (or lower, if a threshold or score is) up to the base score (or higher). */
export function scaleOf(baseScore: number, permanentThreshold: number, score: number): Scale {
  const min = Math.min(0, permanentThreshold, score)
  const max = Math.max(baseScore, score, min + 1)
  return {
    min,
    max,
    percent: (value) => Math.min(100, Math.max(0, ((value - min) / (max - min)) * 100)),
  }
}
