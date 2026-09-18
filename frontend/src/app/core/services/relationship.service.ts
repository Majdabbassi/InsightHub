import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateRelationshipRequest,
  DatasetRelationship,
  RelationshipScanResponse,
} from '../models/relationship.model';
import { Page } from '../models/page.model';

@Injectable({ providedIn: 'root' })
export class RelationshipService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${environment.apiUrl}/projects`;

  private readonly relationshipsSignal = signal<DatasetRelationship[]>([]);
  readonly relationships = this.relationshipsSignal.asReadonly();

  getRelationships(projectId: number): Observable<DatasetRelationship[]> {
    return this.http
      .get<Page<DatasetRelationship>>(`${this.apiUrl}/${projectId}/relationships`)
      .pipe(map((page) => page.content),
        tap((relationships) => this.relationshipsSignal.set(relationships)));
  }

  confirmRelationship(
    projectId: number,
    relationshipId: number,
  ): Observable<DatasetRelationship> {
    return this.http
      .put<DatasetRelationship>(
        `${this.apiUrl}/${projectId}/relationships/${relationshipId}/confirm`,
        {},
      )
      .pipe(tap((updated) => this.replaceInSignal(updated)));
  }

  rejectRelationship(
    projectId: number,
    relationshipId: number,
  ): Observable<DatasetRelationship> {
    return this.http
      .put<DatasetRelationship>(
        `${this.apiUrl}/${projectId}/relationships/${relationshipId}/reject`,
        {},
      )
      .pipe(tap((rejected) => this.relationshipsSignal.update((list) =>
        list.filter((relationship) => relationship.id !== rejected.id))));
  }

  createRelationship(
    projectId: number,
    request: CreateRelationshipRequest,
  ): Observable<DatasetRelationship> {
    return this.http
      .post<DatasetRelationship>(
        `${this.apiUrl}/${projectId}/relationships`,
        request,
      )
      .pipe(tap((created) => this.relationshipsSignal.update((list) => [...list, created])));
  }

  deleteRelationship(projectId: number, relationshipId: number): Observable<void> {
    return this.http
      .delete<void>(`${this.apiUrl}/${projectId}/relationships/${relationshipId}`)
      .pipe(tap(() => this.relationshipsSignal.update((list) =>
        list.filter((relationship) => relationship.id !== relationshipId))));
  }

  scanRelationships(projectId: number): Observable<RelationshipScanResponse> {
    return this.http.post<RelationshipScanResponse>(
      `${this.apiUrl}/${projectId}/relationships/scan`,
      {},
    );
  }

  clear(): void {
    this.relationshipsSignal.set([]);
  }

  private replaceInSignal(updated: DatasetRelationship): void {
    this.relationshipsSignal.update((list) =>
      list.map((relationship) =>
        relationship.id === updated.id ? updated : relationship));
  }
}
