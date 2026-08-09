export const createLatestRequestGuard = () => {
  let generation = 0

  const capture = () => {
    const capturedGeneration = generation
    return Object.freeze({
      isCurrent: () => capturedGeneration === generation,
      commit: (effect) => {
        if (capturedGeneration === generation) effect()
      }
    })
  }

  const invalidate = () => {
    generation += 1
  }

  return { capture, invalidate }
}
