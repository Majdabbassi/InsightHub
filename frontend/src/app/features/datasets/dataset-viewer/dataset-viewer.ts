import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  AnalysisColumnStat,
  AnalysisResult,
  SemanticRole,
} from '../../../core/models/analysis.model';import {
  CleanedDatasetResponse,
  CleaningActionType,
  CleaningSuggestion,
  SelectedCleaningAction,
} from '../../../core/models/cleaning.model';
import { CsvPreview, Dataset } from '../../../core/models/dataset.model';
import { DatasetService } from '../../../core/services/dataset.service';
import { TrendInsights } from '../trend-insights/trend-insights';
import { PeriodComparison } from '../period-comparison/period-comparison';
import { PeriodAnomalies } from '../period-anomalies/period-anomalies';
import { TopPerformers } from '../top-performers/top-performers';
import { DatasetDashboard } from '../dataset-dashboard/dataset-dashboard';

type ViewerTab = 'data' | 'quality' | 'explore' | 'insights' | 'clean';
const VIEWER_TABS: readonly ViewerTab[] = ['data', 'quality', 'explore', 'insights', 'clean'];

type AnalysisState = 'not-analyzed' | 'loading' | 'running' | 'ready';

interface CleaningSelection {
  enabled: boolean;
  action: CleaningActionType;
  customValue: string;
}

const ROLE_LABELS: Record<SemanticRole, string> = {
  IDENTIFIER: 'Identifier',
  BOOLEAN: 'Boolean',
  TEMPORAL: 'Date',
  FREE_TEXT: 'Free text',
  CATEGORICAL: 'Categorical',
  NUMERIC_DISCRETE: 'Numeric',
  NUMERIC_CONTINUOUS: 'Numeric',
  CONSTANT: 'Constant',
  EMPTY: 'Empty',
  INCONSISTENT: 'Inconsistent',
};

const ROLE_WARNINGS: Partial<Record<SemanticRole, string>> = {
  INCONSISTENT:
    'This column mixes incompatible value types (e.g. numbers and text). Consider cleaning it.',
  CONSTANT: 'Every row holds the same value — this column carries no information.',
  EMPTY: 'This column contains no data at all.',
};

@Component({
  selector: 'app-dataset-viewer',
  imports: [DatePipe, RouterLink, TrendInsights, PeriodComparison, PeriodAnomalies, TopPerformers, DatasetDashboard],
  templateUrl: './dataset-viewer.html',
  styleUrl: './dataset-viewer.scss',
})
export class DatasetViewer implements OnInit {
  private readonly datasetService = inject(DatasetService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private projectIdValue!: number;
  private readonly datasetIdSignal = signal<number | null>(null);
  readonly datasetId = this.datasetIdSignal.asReadonly();

  /** Workspace tabs; synced to the ?tab= query param for shareable links. */
  readonly activeTab = signal<ViewerTab>('data');
  private explicitTabChosen = false;

  selectTab(tab: ViewerTab): void {
    this.explicitTabChosen = true;
    this.activeTab.set(tab);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  private applyQueryParamTab(tab: string | null): void {
    if (tab && (VIEWER_TABS as readonly string[]).includes(tab)) {
      const next = tab as ViewerTab;
      if (next !== this.activeTab()) {
        this.explicitTabChosen = true;
        this.activeTab.set(next);
      }
    }
  }

  /** Auto-focus the most relevant tab when no explicit tab was requested. */
  private autoRevealInsights(): void {
    // Never override an explicit ?tab= already present in the URL.
    if (/[?&]tab=/.test(this.router.url)) return;
    if (!this.explicitTabChosen && this.activeTab() === 'data') {
      this.activeTab.set('insights');
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { tab: 'insights' },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    }
  }

  get projectId(): number {
    return this.projectIdValue;
  }

  readonly rowOptions = [10, 25, 50, 100];

  readonly dataset = signal<Dataset | null>(null);
  readonly preview = signal<CsvPreview | null>(null);
  readonly loading = signal(true);
  readonly downloading = signal(false);
  readonly errorMessage = signal('');
  readonly previewRows = signal(50);

  readonly analysisState = signal<AnalysisState>('loading');
  readonly analysis = signal<AnalysisResult | null>(null);
  readonly analysisError = signal('');

  // ===== Cleaning =====
  readonly cleaningOpen = signal(false);
  readonly cleaningLoading = signal(false);
  readonly cleaningRunning = signal(false);
  readonly cleaningError = signal('');
  readonly suggestions = signal<CleaningSuggestion[]>([]);
  readonly selections = signal<Record<string, CleaningSelection>>({});
  readonly cleanedResult = signal<CleanedDatasetResponse | null>(null);

  readonly columnIndexes = computed(() =>
    this.preview()?.columns.map((_, index) => index) ?? []
  );

  ngOnInit(): void {
    this.applyQueryParamTab(this.route.snapshot.queryParamMap.get('tab'));
    this.route.queryParamMap.subscribe((qp) =>
      this.applyQueryParamTab(qp.get('tab'))
    );

    this.route.paramMap.subscribe((params) => {
      const projectId = Number(params.get('id'));
      const datasetId = Number(params.get('datasetId'));

      if (!projectId || !datasetId) {
        this.errorMessage.set('Invalid dataset link.');
        this.loading.set(false);
        return;
      }

      if (projectId === this.projectIdValue && datasetId === this.datasetIdSignal()) {
        return;
      }

      this.projectIdValue = projectId;
      this.datasetIdSignal.set(datasetId);
      this.resetForNavigation();
      this.load(datasetId);
    });
  }

  /** Clears per-dataset state when navigating between datasets in place. */
  private resetForNavigation(): void {
    this.errorMessage.set('');
    this.explicitTabChosen = false;
    this.activeTab.set('data');
    this.analysisState.set('loading');
    this.analysis.set(null);
    this.analysisError.set('');
    this.cleaningOpen.set(false);
    this.cleaningLoading.set(false);
    this.cleaningRunning.set(false);
    this.cleaningError.set('');
    this.suggestions.set([]);
    this.selections.set({});
    this.cleanedResult.set(null);
  }

  private load(datasetId: number): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.datasetService.getDataset(this.projectIdValue, datasetId).subscribe({
      next: (dataset) => {
        this.dataset.set(dataset);
        this.datasetService.getPreview(this.projectIdValue, datasetId, this.previewRows()).subscribe({
          next: (preview) => {
            this.preview.set(preview);
            this.loading.set(false);
            this.checkForStoredAnalysis(datasetId);
          },
          error: () => {
            // Metadata loaded but preview failed — still show the header + download.
            this.errorMessage.set('Could not load the CSV preview. You can still download the file.');
            this.loading.set(false);
          },
        });
      },
      error: () => {
        this.errorMessage.set('Could not load this dataset. It may not exist or you may not have access.');
        this.loading.set(false);
      },
    });
  }

  onRowsChange(event: Event): void {
    const value = Number((event.target as HTMLSelectElement).value);
    if (value && value !== this.previewRows()) {
      this.previewRows.set(value);
      this.reloadPreview();
    }
  }

  reloadPreview(): void {
    const id = this.datasetId();
    if (!id) return;

    this.loading.set(true);
    this.errorMessage.set('');

    this.datasetService.getPreview(this.projectIdValue, id, this.previewRows()).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('Could not load the CSV preview.');
        this.loading.set(false);
      },
    });
  }

  download(): void {
    const dataset = this.dataset();
    if (!dataset || this.downloading()) return;

    this.downloading.set(true);
    this.datasetService.downloadFile(this.projectIdValue, dataset.id).subscribe({
      next: (blob) => {
        this.datasetService.saveBlob(blob, dataset.originalFilename || 'dataset.csv');
        this.downloading.set(false);
      },
      error: () => {
        this.downloading.set(false);
        this.errorMessage.set('Download failed. Please try again.');
      },
    });
  }

  // ===== Analysis =====

  private checkForStoredAnalysis(datasetId: number): void {
    this.analysisState.set('loading');
    this.analysisError.set('');

    this.datasetService.getAnalysis(this.projectIdValue, datasetId).subscribe({
      next: (result) => {
        this.analysis.set(result);
        this.analysisState.set('ready');
        this.autoRevealInsights();
      },
      error: () => {
        this.analysis.set(null);
        this.analysisState.set('not-analyzed');
      },
    });
  }

  runAnalysis(): void {
    const id = this.datasetId();
    if (!id || this.analysisState() === 'running') return;

    this.analysisError.set('');
    this.analysisState.set('running');

    this.datasetService.analyzeDataset(this.projectIdValue, id).subscribe({
      next: (result) => {
        this.analysis.set(result);
        this.analysisState.set('ready');
        this.autoRevealInsights();
        this.refreshDatasetMetadata(id);
      },
      error: (err: HttpErrorResponse) => {
        this.analysisState.set(this.analysis() ? 'ready' : 'not-analyzed');
        this.analysisError.set(this.friendlyAnalysisError(err));
      },
    });
  }

  /** Row/column counts changed server-side after analysis — refresh quietly. */
  private refreshDatasetMetadata(datasetId: number): void {
    this.datasetService.getDataset(this.projectIdValue, datasetId).subscribe({
      next: (fresh) => this.dataset.update((current) => (current ? fresh : current)),
      error: () => undefined,
    });
  }

  private friendlyAnalysisError(err: HttpErrorResponse): string {
    if (err.status === 503) {
      return 'The analytics service is currently unavailable. Please try again in a moment.';
    }
    if (err.status === 422) {
      return err.error?.message ?? 'This file could not be analyzed as a CSV.';
    }
    if (err.status === 404) {
      return 'This dataset no longer exists.';
    }
    return err.error?.message ?? 'Analysis failed. Please try again.';
  }

  // ===== Cleaning =====

  toggleCleaning(): void {
    if (!this.cleaningOpen()) {
      this.cleaningOpen.set(true);
      this.loadCleaningSuggestions();
    } else {
      this.cleaningOpen.set(false);
      this.cleaningError.set('');
    }
  }

  loadCleaningSuggestions(): void {
    const id = this.datasetId();
    if (!id) return;

    this.cleaningLoading.set(true);
    this.cleaningError.set('');
    this.cleanedResult.set(null);

    this.datasetService.getCleaningSuggestions(this.projectIdValue, id).subscribe({
      next: (suggestions) => {
        this.suggestions.set(suggestions);
        const selections: Record<string, CleaningSelection> = {};
        for (const suggestion of suggestions) {
          selections[suggestion.id] = {
            enabled: true,
            action: suggestion.suggestedAction,
            customValue: '',
          };
        }
        this.selections.set(selections);
        this.cleaningLoading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.cleaningLoading.set(false);
        this.cleaningError.set(this.cleaningFriendlyError(err));
      },
    });
  }

  enabledCount(): number {
    let count = 0;
    for (const selection of Object.values(this.selections())) {
      if (selection.enabled) count++;
    }
    return count;
  }

  onToggleSuggestion(id: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.selections.update((current) => ({
      ...current,
      [id]: { ...current[id], enabled: checked },
    }));
  }

  onActionChange(id: string, event: Event): void {
    const action = (event.target as HTMLSelectElement).value as CleaningActionType;
    this.selections.update((current) => ({
      ...current,
      [id]: { ...current[id], action },
    }));
  }

  onCustomValueChange(id: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.selections.update((current) => ({
      ...current,
      [id]: { ...current[id], customValue: value },
    }));
  }

  needsCustomValue(selection: CleaningSelection): boolean {
    return selection.action === CleaningActionType.FILL_CUSTOM_VALUE;
  }

  applyCleaning(): void {
    const id = this.datasetId();
    if (!id || this.cleaningRunning()) return;

    const actions: SelectedCleaningAction[] = [];
    const entries = Object.entries(this.selections());
    for (const [suggestionId, selection] of entries) {
      if (!selection.enabled) continue;
      const suggestion = this.suggestions().find((s) => s.id === suggestionId);
      if (!suggestion) continue;

      actions.push({
        columnName: suggestion.columnName,
        actionType: selection.action,
        customValue: this.needsCustomValue(selection) ? selection.customValue : null,
      });
    }

    if (!actions.length) {
      this.cleaningError.set('Select at least one cleaning action to continue.');
      return;
    }

    this.cleaningRunning.set(true);
    this.cleaningError.set('');

    this.datasetService.applyCleaning(this.projectIdValue, id, actions).subscribe({
      next: (response) => {
        this.cleanedResult.set(response);
        this.cleaningRunning.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.cleaningRunning.set(false);
        this.cleaningError.set(this.cleaningFriendlyError(err));
      },
    });
  }

  private cleaningFriendlyError(err: HttpErrorResponse): string {
    if (err.status === 0 || err.status === 503) {
      return 'The analytics service is currently unavailable. Please try again in a moment.';
    }
    return err.error?.message ?? 'Cleaning failed. Please try again.';
  }

  cleaningActionLabel(action: CleaningActionType): string {
    return this.datasetService.cleaningActionLabel(action);
  }

  formatSize(bytes: number): string {
    if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${bytes} B`;
  }

  formatNumber(value: number | null | undefined): string {
    return value === null || value === undefined ? '—' : value.toLocaleString();
  }

  isNumericColumn(column: AnalysisColumnStat): boolean {
    if (column.semanticRole) {
      return (
        column.semanticRole === 'NUMERIC_DISCRETE' ||
        column.semanticRole === 'NUMERIC_CONTINUOUS'
      );
    }
    return column.dataType === 'integer' || column.dataType === 'float';
  }

  topValuesText(column: AnalysisColumnStat): string {
    return (column.topValues ?? [])
      .map((tv) => `${truncate(tv.value, 20)} (${tv.count})`)
      .join(', ');
  }

  // ===== Semantic roles =====

  roleLabel(column: AnalysisColumnStat): string {
    return column.semanticRole
      ? (ROLE_LABELS[column.semanticRole] ?? column.semanticRole)
      : '';
  }

  // ===== Outliers =====

  hasOutliers(column: AnalysisColumnStat): boolean {
    return !!column.outlierAnalysis && column.outlierAnalysis.outlierCount > 0;
  }

  // ===== Correlations =====

  strengthLabel(strength: string): string {
    const labels: Record<string, string> = {
      MODERATE: 'Moderate',
      STRONG: 'Strong',
      VERY_STRONG: 'Very strong',
    };
    return labels[strength] ?? strength;
  }

  // ===== Data quality =====

  gradeTier(grade: string): 'good' | 'fair' | 'poor' {
    if (grade === 'A' || grade === 'B') {
      return 'good';
    }
    return grade === 'C' ? 'fair' : 'poor';
  }

  qualityPercent(value: number): number {
    return Math.round(value * 100);
  }

  outlierSummaryText(column: AnalysisColumnStat): string {
    const oa = column.outlierAnalysis;
    if (!oa || oa.outlierCount === 0) {
      return '';
    }
    const plural = oa.outlierCount === 1 ? 'outlier' : 'outliers';
    return oa.extremeCount > 0
      ? `${oa.outlierCount} ${plural} (${oa.extremeCount} extreme)`
      : `${oa.outlierCount} ${plural}`;
  }

  roleClass(column: AnalysisColumnStat): string {
    return column.semanticRole ? column.semanticRole.toLowerCase() : '';
  }

  /** Roles that are data-quality signals in themselves. */
  needsAttention(column: AnalysisColumnStat): boolean {
    return !!column.semanticRole && ROLE_WARNINGS[column.semanticRole] !== undefined;
  }

  roleWarning(column: AnalysisColumnStat): string {
    return column.semanticRole ? (ROLE_WARNINGS[column.semanticRole] ?? '') : '';
  }

  confidencePercent(column: AnalysisColumnStat): string {
    return `${Math.round((column.confidence ?? 0) * 100)}%`;
  }

  invalidValuesText(column: AnalysisColumnStat): string {
    return `${column.invalidValueCount ?? 0} value(s) did not match this column's detected type.`;
  }

  readonly expandedColumns = signal<Set<string>>(new Set());

  toggleColumnDetails(name: string): void {
    this.expandedColumns.update((current) => {
      const next = new Set(current);
      if (next.has(name)) {
        next.delete(name);
      } else {
        next.add(name);
      }
      return next;
    });
  }

  isExpanded(name: string): boolean {
    return this.expandedColumns().has(name);
  }
}

function truncate(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max)}…` : value;
}
