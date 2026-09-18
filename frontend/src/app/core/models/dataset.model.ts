export interface Dataset {
  id: number;
  name: string;
  originalFilename: string;
  fileSizeBytes: number;
  rowCount: number | null;
  columnCount: number | null;
  uploadedAt: string;
  projectId: number;
  sourceDatasetId: number | null;
  isCleanedVersion: boolean;
}

export interface CsvPreview {
  columns: string[];
  rows: string[][];
}
