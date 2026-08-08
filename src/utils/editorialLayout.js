export function desktopContentWidth(viewportWidth) {
  return Math.max(0, Math.min(1180, viewportWidth - 32))
}
