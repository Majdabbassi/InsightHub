import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateProjectRequest,
  Project,
  UpdateProjectRequest,
} from '../models/project.model';
import { Page } from '../models/page.model';

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${environment.apiUrl}/projects`;

  private readonly projectsSignal = signal<Project[]>([]);
  readonly projects = this.projectsSignal.asReadonly();

  getProjects(): Observable<Project[]> {
    return this.http
      .get<Page<Project>>(this.apiUrl)
      .pipe(map((page) => page.content), tap((projects) => this.projectsSignal.set(projects)));
  }

  getProject(id: number): Observable<Project> {
    return this.http.get<Project>(`${this.apiUrl}/${id}`);
  }

  createProject(request: CreateProjectRequest): Observable<Project> {
    return this.http.post<Project>(this.apiUrl, request).pipe(
      tap((created) => this.projectsSignal.update((list) => [...list, created]))
    );
  }

  updateProject(id: number, request: UpdateProjectRequest): Observable<Project> {
    return this.http.put<Project>(`${this.apiUrl}/${id}`, request).pipe(
      tap((updated) =>
        this.projectsSignal.update((list) =>
          list.map((project) => (project.id === id ? updated : project))
        )
      )
    );
  }

  deleteProject(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`).pipe(
      tap(() =>
        this.projectsSignal.update((list) => list.filter((project) => project.id !== id))
      )
    );
  }
}
