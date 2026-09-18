export enum CleaningActionType {
  DROP_ROWS = 'DROP_ROWS',
  DROP_DUPLICATES = 'DROP_DUPLICATES',
  FILL_MEAN = 'FILL_MEAN',
  FILL_MEDIAN = 'FILL_MEDIAN',
  FILL_ZERO = 'FILL_ZERO',
  FILL_MODE = 'FILL_MODE',
  FILL_CUSTOM_VALUE = 'FILL_CUSTOM_VALUE',
  COERCE_TYPE = 'COERCE_TYPE',
  DROP_INVALID_ROWS = 'DROP_INVALID_ROWS',
  CAP_TO_BOUNDS = 'CAP_TO_BOUNDS',
  REMOVE_ROWS = 'REMOVE_ROWS',
  SET_TO_ZERO = 'SET_TO_ZERO',
  FLAG_ONLY = 'FLAG_ONLY',
  NONE = 'NONE',
}

export type SuggestionType =
  | 'MISSING_VALUES'
  | 'DUPLICATES'
  | 'DATA_TYPE_MISMATCH'
  | 'OUTLIERS'
  | 'VALIDATION_ISSUE'
  | 'LOW_VARIANCE';

export interface CleaningSuggestion {
  id: string;
  type: SuggestionType;
  columnName: string | null;
  description: string;
  suggestedAction: CleaningActionType;
  alternativeActions: CleaningActionType[];
  affectedRowCount: number;
  reasoning?: string | null;
}

export interface SelectedCleaningAction {
  columnName: string | null;
  actionType: CleaningActionType;
  customValue?: string | null;
}

export interface CleanedDatasetResponse {
  jobId: number;
  sourceDatasetId: number;
  cleanedDataset: {
    id: number;
    name: string;
    originalFilename: string;
    rowCount: number | null;
    columnCount: number | null;
    uploadedAt: string;
  };
  rowsBefore: number;
  rowsAfter: number;
  rowsRemoved: number;
  valuesFilled: number;
}