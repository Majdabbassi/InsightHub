import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AnomaliesResponse,
  PeriodComparisonResponse,
  PerformersResponse,
  RelationalInsightsResponse,
  SiblingComparisonResponse,
  TrendInsightsResponse,
} from '../models/insight.model';

export interface PeriodComparisonParams {
  periodType?: string;
  customCurrentStart?: string;
  customCurrentEnd?: string;
  customPreviousStart?: string;
  customPreviousEnd?: string;
}

@Injectable({ providedIn: 'root' })
export class InsightService {
  private readonly http = inject(HttpClient);

  getTrends(projectId: number, datasetId: number): Observable<TrendInsightsResponse> {
    return this.http.get<TrendInsightsResponse>(
      `${environment.apiUrl}/projects/${projectId}/datasets/${datasetId}/insights/trends`,
    );
  }

  getPerformers(projectId: number, datasetId: number): Observable<PerformersResponse> {
    return this.http.get<PerformersResponse>(
      `${environment.apiUrl}/projects/${projectId}/datasets/${datasetId}/insights/top-bottom-performers`,
    );
  }

  getAnomalies(projectId: number, datasetId: number): Observable<AnomaliesResponse> {
    return this.http.get<AnomaliesResponse>(
      `${environment.apiUrl}/projects/${projectId}/datasets/${datasetId}/insights/anomalies`,
    );
  }

  getPeriodComparison(
    projectId: number,
    datasetId: number,
    params: PeriodComparisonParams = {},
  ): Observable<PeriodComparisonResponse> {
    const httpParams: Record<string, string> = {};
    for (const [key, value] of Object.entries(params)) {
      if (value) {
        httpParams[key] = value;
      }
    }
    return this.http.get<PeriodComparisonResponse>(
      `${environment.apiUrl}/projects/${projectId}/datasets/${datasetId}/insights/period-comparison`,
      { params: httpParams },
    );
  }

  getSiblingComparison(
    projectId: number,
    datasetAId: number,
    datasetBId: number,
  ): Observable<SiblingComparisonResponse> {
    return this.http.get<SiblingComparisonResponse>(
      `${environment.apiUrl}/projects/${projectId}/insights/sibling-comparison`,
      { params: { datasetAId, datasetBId } },
    );
  }

  getRelationalInsights(
    projectId: number,
    relationshipId: number,
  ): Observable<RelationalInsightsResponse> {
    return this.http.get<RelationalInsightsResponse>(
      `${environment.apiUrl}/projects/${projectId}/relationships/${relationshipId}/insights`,
    );
  }
}
