export interface KpiResponse {
  totalRows: number;
  totalColumns: number;
  missingValuesPercent: number;
  duplicateRowsPercent: number;
}

export type ChartType = 'LINE' | 'BAR' | 'PIE' | 'HISTOGRAM' | 'SCATTER';

export interface ChartSuggestion {
  id: string;
  type: ChartType;
  title: string;
  xColumn: string;
  yColumn: string | null;
  aggregation: string;
  featured: boolean;
  relevanceScore: number;
  crossColumn: boolean;
  correlation?: number | null;
}

export interface DashboardResponse {
  kpis: KpiResponse;
  suggestedCharts: ChartSuggestion[];
}

export interface ChartPoint {
  x: number;
  y: number;
}

export interface ChartDataResponse {
  labels: string[];
  values: number[];
  points?: ChartPoint[] | null;
}
