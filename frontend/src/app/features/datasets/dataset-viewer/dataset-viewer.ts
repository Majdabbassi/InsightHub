import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AnalysisResult } from '../../../core/models/analysis.model';
import {
  CleanedDatasetResponse,
  CleaningActionType,
  CleaningSuggestion,
  SelectedCleaningAction,
} from '../../../core/models/cleaning.model';
import { CsvPreview, Dataset } from '../../../core/models/dataset.model';
import { DatasetService } from '../../../core/services/dataset.service';
import { friendlyErrorMessage, httpErrorStatus, readErrorMessage } from '../../../shared/errors';
import { formatBytes } from '../../../shared/format';
import { TrendInsights } from '../trend-insights/trend-insights';
import { PeriodComparison } from '../period-comparison/period-comparison';
import { PeriodAnomalies } from '../period-anomalies/period-anomalies';
import { TopPerformers } from '../top-performers/top-performers';
import { DatasetDashboard } from '../dataset-dashboard/dataset-dashboard';
import { PreviewTable } from './preview-table/preview-table';
import { QualityAnalysis } from './quality-analysis/quality-analysis';
import { CleaningPanel } from './cleaning-panel/cleaning-panel';
import {
  ActionChangeEvent,
  AnalysisState,
  CleaningSelection,
  CustomValueChangeEvent,
  ToggleSuggestionEvent,
  ViewerTab,
  VIEWER_TABS,
} from './dataset-viewer.types';

@Component({
  selector: 'app-dataset-viewer',
  imports: [
    DatePipe,
    RouterLink,
    PreviewTable,
    QualityAnalysis,
    CleaningPanel,
    TrendInsights,
    PeriodComparison,
    PeriodAnomalies,
    TopPerformers,
    DatasetDashboard,
  ],
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

  /** Arrow-key / Home / End navigation for the tablist (WAI-ARIA tabs pattern). */
  onTabKeydown(event: KeyboardEvent, current: ViewerTab): void {
    const index = VIEWER_TABS.indexOf(current);
    if (index === -1) {
      return;
    }
    let nextIndex = index;
    switch (event.key) {
      case 'ArrowLeft':
      case 'ArrowUp':
        nextIndex = (index - 1 + VIEWER_TABS.length) % VIEWER_TABS.length;
        break;
      case 'ArrowRight':
      case 'ArrowDown':
        nextIndex = (index + 1) % VIEWER_TABS.length;
        break;
      case 'Home':
        nextIndex = 0;
        break;
      case 'End':
        nextIndex = VIEWER_TABS.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.selectTab(VIEWER_TABS[nextIndex]);
    const target = document.getElementById(`tab-${VIEWER_TABS[nextIndex]}`);
    target?.focus();
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

  onRowsChange(rows: number): void {
    if (rows !== this.previewRows()) {
      this.previewRows.set(rows);
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

  formatSize(bytes: number): string {
    return formatBytes(bytes);
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
    const status = httpErrorStatus(err);
    if (status === 422) {
      return readErrorMessage(err) ?? 'This file could not be analyzed as a CSV.';
    }
    if (status === 404) {
      return 'This dataset no longer exists.';
    }
    return friendlyErrorMessage(
      err,
      'Analysis failed. Please try again.',
      'The analytics service is currently unavailable. Please try again in a moment.',
    );
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
        this.cleaningError.set(
          friendlyErrorMessage(
            err,
            'Cleaning failed. Please try again.',
            'The analytics service is currently unavailable. Please try again in a moment.',
          ),
        );
      },
    });
  }

  onToggleSuggestion(event: ToggleSuggestionEvent): void {
    this.selections.update((current) => ({
      ...current,
      [event.id]: { ...current[event.id], enabled: event.checked },
    }));
  }

  onActionChange(event: ActionChangeEvent): void {
    this.selections.update((current) => ({
      ...current,
      [event.id]: { ...current[event.id], action: event.action },
    }));
  }

  onCustomValueChange(event: CustomValueChangeEvent): void {
    this.selections.update((current) => ({
      ...current,
      [event.id]: { ...current[event.id], customValue: event.value },
    }));
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
        customValue: selection.action === CleaningActionType.FILL_CUSTOM_VALUE
          ? selection.customValue
          : null,
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
        this.cleaningError.set(
          friendlyErrorMessage(
            err,
            'Cleaning failed. Please try again.',
            'The analytics service is currently unavailable. Please try again in a moment.',
          ),
        );
      },
    });
  }
}