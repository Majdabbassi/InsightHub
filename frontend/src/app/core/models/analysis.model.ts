export interface AnalysisTopValue {
  value: string;
  count: number;
}

export interface OutlierSample {
  value: number;
  rowIndex: number;
}

export interface OutlierAnalysis {
  outlierCount: number;
  mildCount: number;
  extremeCount: number;
  outlierPercentage: number;
  lowerBound: number;
  upperBound: number;
  outlierSamples?: OutlierSample[];
  lowConfidence?: boolean;
}

export type SemanticRole =
  | 'IDENTIFIER'
  | 'BOOLEAN'
  | 'TEMPORAL'
  | 'FREE_TEXT'
  | 'CATEGORICAL'
  | 'NUMERIC_DISCRETE'
  | 'NUMERIC_CONTINUOUS'
  | 'CONSTANT'
  | 'EMPTY'
  | 'INCONSISTENT';

export interface AnalysisColumnStat {
  name: string;
  dataType: string;
  /** Semantic meaning of the column (may be absent in pre-upgrade results). */
  semanticRole?: SemanticRole;
  confidence?: number;
  reasoning?: string;
  invalidValueCount?: number;
  missingCount: number;
  missingPercentage: number;
  uniqueCount: number;
  min?: number | null;
  max?: number | null;
  mean?: number | null;
  median?: number | null;
  stdDev?: number | null;
  topValues?: AnalysisTopValue[] | null;
  outlierAnalysis?: OutlierAnalysis | null;
}

export interface ColumnCorrelation {
  columnA: string;
  columnB: string;
  correlation: number;
  strength: 'MODERATE' | 'STRONG' | 'VERY_STRONG';
  direction: 'POSITIVE' | 'NEGATIVE';
  sampleSize: number;
  lowConfidence?: boolean;
}

export interface DataQualityBreakdown {
  completeness: number;
  uniqueness: number;
  consistency: number;
  validity: number;
}

export interface DataQuality {
  overallScore: number;
  grade: 'A' | 'B' | 'C' | 'D' | 'F';
  breakdown: DataQualityBreakdown;
}

export interface AnalysisResult {
  id: number;
  datasetId: number;
  analyzedAt: string;
  rowCount: number;
  columnCount: number;
  duplicateRowCount: number;
  totalMissingValues: number;
  columns: AnalysisColumnStat[];
  /** Present in analyses made after correlations were introduced. */
  correlations?: ColumnCorrelation[] | null;
  /** Present in analyses made after the quality score was introduced. */
  dataQuality?: DataQuality | null;
}
