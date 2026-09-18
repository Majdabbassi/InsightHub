import { ChartConfiguration } from 'chart.js';

/** Dark-theme chart palette: bright, saturated series that read on dark surfaces. */
const SERIES = [
  '#818cf8', '#34d399', '#fbbf24', '#f87171', '#60a5fa',
  '#a78bfa', '#fb923c', '#22d3ee', '#f472b6', '#a3e635',
];

export const CHART_ACCENT = SERIES[0];

/** Deterministic categorical color for the i-th series. */
export function chartColor(index: number): string {
  return SERIES[index % SERIES.length];
}

/** Translucent fill variant of a series color (for area/line fills). */
export function chartFill(index: number, alpha = 0.18): string {
  const hex = chartColor(index);
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

type Loose = Record<string, any>;

/**
 * Applies dark-theme defaults (ticks, grid, tooltip) to any Chart.js config.
 * Values already set by the caller win — only unset knobs get themed.
 * Mutates and returns the same options object for convenience.
 */
export function applyChartTheme<T extends ChartConfiguration>(config: T): T {
  const tickColor = '#7d8b9d';
  const gridColor = 'rgba(148, 163, 184, 0.12)';
  const legendColor = '#aab6c5';

  const options = (config.options ?? {}) as Loose;
  config.options = options;

  if (options['responsive'] === undefined) {
    options['responsive'] = true;
  }
  if (options['maintainAspectRatio'] === undefined) {
    options['maintainAspectRatio'] = false;
  }

  const plugins = (options['plugins'] ?? {}) as Loose;
  options['plugins'] = plugins;

  if (plugins['legend'] !== false) {
    const legend = (plugins['legend'] ?? {}) as Loose;
    plugins['legend'] = legend;
    const labels = legend['labels'] as Loose | undefined;
    if (labels === undefined || labels['color'] === undefined) {
      legend['labels'] = { ...labels, color: legendColor };
    }
  }

  const tooltip = plugins['tooltip'] as Loose | undefined;
  if (tooltip === undefined || tooltip['backgroundColor'] === undefined) {
    plugins['tooltip'] = {
      ...tooltip,
      backgroundColor: '#1b2230',
      borderColor: '#35414f',
      borderWidth: 1,
      titleColor: '#e6ebf2',
      bodyColor: '#aab6c5',
    };
  }

  const scales = options['scales'] as Loose | undefined;
  if (scales) {
    for (const key of Object.keys(scales)) {
      const scale = scales[key] as Loose;
      if (!scale) continue;
      const ticks = scale['ticks'] as Loose | undefined;
      if (ticks === undefined || ticks['color'] === undefined) {
        scale['ticks'] = { ...ticks, color: tickColor };
      }
      const grid = scale['grid'] as Loose | undefined;
      if (grid === undefined || grid['color'] === undefined) {
        scale['grid'] = { ...grid, color: gridColor };
      }
    }
  }

  return config;
}
