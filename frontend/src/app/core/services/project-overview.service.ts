import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ProjectOverview } from '../models/project-overview.model';

@Injectable({ providedIn: 'root' })
export class ProjectOverviewService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${environment.apiUrl}/projects`;

  private readonly overviewSignal = signal<ProjectOverview | null>(null);
  readonly overview = this.overviewSignal.asReadonly();

  getProjectOverview(projectId: number): Observable<ProjectOverview> {
    return this.http
      .get<ProjectOverview>(`${this.apiUrl}/${projectId}/overview`)
      .pipe(tap((overview) => this.overviewSignal.set(overview)));
  }

  clear(): void {
    this.overviewSignal.set(null);
  }
}
