import { HttpClient, HttpEvent } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AnalysisResult } from '../models/analysis.model';
import {
  CleanedDatasetResponse,
  CleaningActionType,
  CleaningSuggestion,
  SelectedCleaningAction,
} from '../models/cleaning.model';
import { ChartDataResponse, DashboardResponse } from '../models/dashboard.model';
import { CsvPreview, Dataset } from '../models/dataset.model';
import { Page } from '../models/page.model';

@Injectable({ providedIn: 'root' })
export class DatasetService {
  private readonly http = inject(HttpClient);

  private readonly datasetsSignal = signal<Dataset[]>([]);
  readonly datasets = this.datasetsSignal.asReadonly();

  private apiUrl(projectId: number): string {
    return `${environment.apiUrl}/projects/${projectId}/datasets`;
  }

  getDatasets(projectId: number): Observable<Dataset[]> {
    return this.http
      .get<Page<Dataset>>(this.apiUrl(projectId))
      .pipe(map((page) => page.content), tap((datasets) => this.datasetsSignal.set(datasets)));
  }

  getDataset(projectId: number, datasetId: number): Observable<Dataset> {
    return this.http.get<Dataset>(`${this.apiUrl(projectId)}/${datasetId}`);
  }

  getPreview(projectId: number, datasetId: number, rows = 50): Observable<CsvPreview> {
    const params = { rows: String(rows) };
    return this.http.get<CsvPreview>(`${this.apiUrl(projectId)}/${datasetId}/preview`, { params });
  }

  /** Fetches the file as a blob (Authorization header attached by interceptor). */
  downloadFile(projectId: number, datasetId: number): Observable<Blob> {
    return this.http.get(`${this.apiUrl(projectId)}/${datasetId}/download`, {
      responseType: 'blob',
    });
  }

  /** Triggers a browser download of the given blob. */
  saveBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  }

  /** Runs analysis via the backend + analytics microservice. */
  analyzeDataset(projectId: number, datasetId: number): Observable<AnalysisResult> {
    return this.http.post<AnalysisResult>(
      `${this.apiUrl(projectId)}/${datasetId}/analyze`,
      {}
    );
  }

  /** Loads a previously stored analysis result (404 if not yet analyzed). */
  getAnalysis(projectId: number, datasetId: number): Observable<AnalysisResult> {
    return this.http.get<AnalysisResult>(
      `${this.apiUrl(projectId)}/${datasetId}/analysis`
    );
  }

  /** Multipart upload with progress events (UploadProgress / Response). */
  uploadDataset(projectId: number, file: File): Observable<HttpEvent<Dataset>> {
    const formData = new FormData();
    formData.append('file', file, file.name);

    return this.http.post<Dataset>(this.apiUrl(projectId), formData, {
      reportProgress: true,
      observe: 'events',
    });
  }

  addDatasetToState(dataset: Dataset): void {
    this.datasetsSignal.update((list) => [...list, dataset]);
  }

  deleteDataset(projectId: number, datasetId: number): Observable<void> {
    return this.http
      .delete<void>(`${this.apiUrl(projectId)}/${datasetId}`)
      .pipe(
        tap(() =>
          this.datasetsSignal.update((list) =>
            list.filter((dataset) => dataset.id !== datasetId)
          )
        )
      );
  }

  // ===== Cleaning =====

  getCleaningSuggestions(
    projectId: number,
    datasetId: number
  ): Observable<CleaningSuggestion[]> {
    return this.http.get<CleaningSuggestion[]>(
      `${this.apiUrl(projectId)}/${datasetId}/clean/suggestions`
    );
  }

  applyCleaning(
    projectId: number,
    datasetId: number,
    actions: SelectedCleaningAction[]
  ): Observable<CleanedDatasetResponse> {
    return this.http.post<CleanedDatasetResponse>(
      `${this.apiUrl(projectId)}/${datasetId}/clean/apply`,
      actions
    );
  }

  readonly cleaningActionLabels: Record<CleaningActionType, string> = {
    [CleaningActionType.DROP_ROWS]: 'Drop rows with missing values',
    [CleaningActionType.DROP_DUPLICATES]: 'Remove duplicate rows',
    [CleaningActionType.FILL_MEAN]: 'Fill with column mean',
    [CleaningActionType.FILL_MEDIAN]: 'Fill with column median',
    [CleaningActionType.FILL_ZERO]: 'Fill with zero',
    [CleaningActionType.FILL_MODE]: 'Fill with most common value',
    [CleaningActionType.FILL_CUSTOM_VALUE]: 'Fill with custom value',
    [CleaningActionType.COERCE_TYPE]: 'Convert to detected type',
    [CleaningActionType.DROP_INVALID_ROWS]: 'Drop rows with invalid values',
    [CleaningActionType.CAP_TO_BOUNDS]: 'Cap outliers to normal range',
    [CleaningActionType.REMOVE_ROWS]: 'Remove outlier rows',
    [CleaningActionType.SET_TO_ZERO]: 'Set negative values to zero',
    [CleaningActionType.FLAG_ONLY]: 'Flag only (no change)',
    [CleaningActionType.NONE]: 'No automatic fix available',
  };

  cleaningActionLabel(action: CleaningActionType): string {
    return this.cleaningActionLabels[action] ?? action;
  }

  // ===== Dashboard =====

  getDashboard(projectId: number, datasetId: number): Observable<DashboardResponse> {
    return this.http.get<DashboardResponse>(
      `${this.apiUrl(projectId)}/${datasetId}/dashboard`
    );
  }

  getChartData(
    projectId: number,
    datasetId: number,
    xColumn: string,
    yColumn: string | null,
    type: string,
    aggregation: string
  ): Observable<ChartDataResponse> {
    const params: Record<string, string> = {
      xColumn,
      type,
      aggregation,
    };
    if (yColumn) params['yColumn'] = yColumn;
    return this.http.get<ChartDataResponse>(
      `${this.apiUrl(projectId)}/${datasetId}/chart-data`,
      { params }
    );
  }
}