export type TrendDirection = 'INCREASING' | 'DECREASING' | 'STABLE';
export type TrendStrength = 'CLEAR' | 'MODERATE' | 'NOISY';

export interface TrendInsight {
  column: string;
  temporalColumn: string;
  periodGrouping: 'day' | 'week' | 'month' | 'quarter';
  periodCount: number;
  direction: TrendDirection;
  strength: TrendStrength;
  rSquared: number;
  percentageChange: number | null;
  firstPeriodLabel: string;
  lastPeriodLabel: string;
  points: number[];
  pointLabels: string[];
  summary: string;
}

export interface TrendInsightsResponse {
  trends: TrendInsight[];
  skippedReasons: string[];
}

export interface ComparisonDriver {
  categoryColumn: string;
  categoryValue: string;
  previousValue: number;
  currentValue: number;
  delta: number;
}

export interface ColumnComparison {
  column: string;
  previousValue: number;
  currentValue: number;
  delta: number;
  percentChange: number | null;
  lowConfidence: boolean;
  topDrivers: ComparisonDriver[];
  summary: string;
}

export interface PeriodComparisonResponse {
  periodType: 'day' | 'week' | 'month' | 'quarter' | 'custom';
  previousLabel: string;
  currentLabel: string;
  comparisons: ColumnComparison[];
  skippedReasons: string[];
}

export type AnomalySeverity = 'SEVERE' | 'MODERATE';
export type AnomalyDirection = 'SPIKE' | 'DROP';

export interface AnomalyDriver {
  categoryColumn: string;
  categoryValue: string;
  periodValue: number;
  typicalValue: number;
}

export interface PeriodAnomaly {
  column: string;
  temporalColumn: string;
  periodGrouping: 'day' | 'week' | 'month' | 'quarter';
  periodLabel: string;
  actualValue: number;
  typicalValue: number;
  zScore: number;
  severity: AnomalySeverity;
  direction: AnomalyDirection;
  multiplier: number | null;
  topDrivers: AnomalyDriver[];
  summary: string;
}

export interface AnomaliesResponse {
  anomalies: PeriodAnomaly[];
  skippedReasons: string[];
}

export interface PerformerEntry {
  category: string;
  sumValue: number;
  avgValue: number;
  rank: number;
}

export interface PerformersPair {
  categoricalColumn: string;
  numericColumn: string;
  aggregation: string;
  topPerformers: PerformerEntry[];
  bottomPerformers: PerformerEntry[];
  gap: number | null;
  isDominant: boolean;
  dominantPercentage: number | null;
  note: string | null;
  summary: string;
}

export interface PerformersResponse {
  performers: PerformersPair[];
  skippedReasons: string[];
}

export interface SiblingComparisonResponse {
  comparisonType: 'sibling';
  datasetALabel: string;
  datasetBLabel: string;
  comparisons: ColumnComparison[];
  skippedReasons: string[];
}

export interface ReferentialCompleteness {
  matchPercentage: number;
  mismatchCount: number;
  mismatchPercentage: number;
  summary: string;
}

export interface JoinAggregate {
  avgChildCountPerParent: number;
  childNumericColumn: string | null;
  avgNumericSumPerParent: number | null;
  summary: string;
}

export interface RelationalInsightsResponse {
  referentialCompleteness: ReferentialCompleteness;
  joinAggregate: JoinAggregate | null;
}
