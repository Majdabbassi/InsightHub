/** Shared formatting helpers. Components keep thin delegating methods so
 *  templates stay unchanged while the logic lives in one place. */

/** Human-readable byte size: 512 B / 12.5 KB / 3.1 MB. */
export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/** Locale-formatted number; renders an em dash for absent values so stats
 *  tables stay readable. Pass options to cap precision (e.g.
 *  `{ maximumFractionDigits: 2 }`). */
export function formatNumber(
  value: number | null | undefined,
  options?: Intl.NumberFormatOptions,
): string {
  if (value === null || value === undefined) return '—';
  return options ? value.toLocaleString('en-US', options) : value.toLocaleString('en-US');
}

/** Renders a percentage already expressed in 0-100 terms (85.3 → "85.3%"). */
export function formatPercent(value: number, decimals = 1): string {
  return `${value.toFixed(decimals)}%`;
}

/** Converts a 0-1 fraction to a percentage (0.853 → "85%"). Pass decimals to
 *  keep sub-integer precision. */
export function formatFractionPercent(
  fraction: number | null | undefined,
  decimals = 0,
): string {
  if (fraction === null || fraction === undefined) return '—';
  return `${(fraction * 100).toFixed(decimals)}%`;
}

/** Ellipsizes long values (e.g. top values in a column cell). */
export function truncate(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max)}…` : value;
}

/** Adds an explicit + sign to positive numbers (change/delta displays). */
export function signedNumber(value: number): string {
  return (value > 0 ? '+' : '') + value.toLocaleString('en-US');
}

/** Signed percentage delta, e.g. +12.5% / -3.0%. */
export function signedPercent(value: number): string {
  return (value > 0 ? '+' : '') + value.toFixed(1) + '%';
}

/** Score-scale formatting for quality scores already on 0-100. */
export function scoreAsInteger(value: number): number {
  return Math.round(value);
}