import type { CleaningActionType } from '../../../core/models/cleaning.model';

/** Workspace tabs for the dataset viewer; synced to the ?tab= query param. */
export type ViewerTab = 'data' | 'quality' | 'explore' | 'insights' | 'clean';

export const VIEWER_TABS: readonly ViewerTab[] = [
  'data',
  'quality',
  'explore',
  'insights',
  'clean',
];

/** Analysis lifecycle state for the current dataset. */
export type AnalysisState = 'not-analyzed' | 'loading' | 'running' | 'ready';

/** Per-suggestion cleaning selection held by the parent and edited by the
 *  cleaning panel through output events. */
export interface CleaningSelection {
  enabled: boolean;
  action: CleaningActionType;
  customValue: string;
}

export interface ToggleSuggestionEvent {
  id: string;
  checked: boolean;
}

export interface ActionChangeEvent {
  id: string;
  action: CleaningActionType;
}

export interface CustomValueChangeEvent {
  id: string;
  value: string;
}