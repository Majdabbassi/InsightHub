export interface QualityGradeBreakdown {
  A: number;
  B: number;
  C: number;
  D: number;
  F: number;
}

export interface OverviewStats {
  datasetCount: number;
  totalRows: number;
  totalColumns: number;
  averageQualityScore: number | null;
  qualityGradeBreakdown: QualityGradeBreakdown;
  lowQualityDatasetCount: number;
}

export interface OverviewDataset {
  id: number;
  name: string;
  rowCount: number;
  columnCount: number;
  qualityScore: number | null;
  qualityGrade: string | null;
  uploadedAt: string;
  isActive: boolean;
  sourceDatasetId: number | null;
  isCleanedVersion: boolean;
}

export interface ProjectOverview {
  activeStats: OverviewStats;
  allVersionsStats: OverviewStats;
  datasets: OverviewDataset[];
}
