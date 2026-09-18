import {
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import {
  DatasetRelationship,
} from '../../../core/models/relationship.model';
import {
  RelationalInsightsResponse,
  SiblingComparisonResponse,
} from '../../../core/models/insight.model';
import { InsightService } from '../../../core/services/insight.service';
import { RelationshipService } from '../../../core/services/relationship.service';

interface ComparisonState {
  loading: boolean;
  error: string;
  result: SiblingComparisonResponse | null;
}

interface FindingState {
  loading: boolean;
  error: string;
  result: RelationalInsightsResponse | null;
}

@Component({
  selector: 'app-project-insights',
  templateUrl: './project-insights.html',
  styleUrl: './project-insights.scss',
})
export class ProjectInsights {
  readonly projectId = input.required<number>();

  private readonly insightService = inject(InsightService);
  private readonly relationshipService = inject(RelationshipService);

  readonly relationships = this.relationshipService.relationships;

  readonly comparisons = signal<Record<number, ComparisonState>>({});
  readonly findings = signal<Record<number, FindingState>>({});

  readonly confirmingId = signal<number | null>(null);
  readonly confirmError = signal('');

  readonly siblingRelationships = computed(() =>
    this.relationships().filter(
      (rel) => rel.relationshipType === 'SIBLING'
        && (rel.status === 'SUGGESTED' || rel.status === 'CONFIRMED'),
    ));

  readonly confirmedForeignKeyRelationships = computed(() =>
    this.relationships().filter(
      (rel) => rel.relationshipType === 'FOREIGN_KEY' && rel.status === 'CONFIRMED',
    ));

  constructor() {
    effect(() => {
      this.syncComparisons();
      this.syncFindings();
    });
  }

  isIncrease(value: number): boolean {
    return value >= 0;
  }

  formatDelta(value: number): string {
    const rounded = Math.round(value * 100) / 100;
    return (rounded > 0 ? '+' : '') + rounded.toLocaleString('en-US');
  }

  formatNumber(value: number): string {
    return value.toLocaleString('en-US', { maximumFractionDigits: 2 });
  }

  formatPercent(value: number | null): string {
    if (value === null) {
      return '—';
    }
    return (value > 0 ? '+' : '') + value.toFixed(1) + '%';
  }

  driverWidth(driver: { delta: number }, drivers: { delta: number }[]): number {
    const max = Math.max(...drivers.map((d) => Math.abs(d.delta)), 1);
    return Math.max(Math.abs(driver.delta) / max * 100, 6);
  }

  confirmSibling(rel: DatasetRelationship): void {
    this.confirmingId.set(rel.id);
    this.confirmError.set('');
    this.relationshipService.confirmRelationship(this.projectId(), rel.id).subscribe({
      next: () => this.confirmingId.set(null),
      error: () => {
        this.confirmingId.set(null);
        this.confirmError.set('Could not confirm this relationship. Please try again.');
      },
    });
  }

  private syncComparisons(): void {
    const current = { ...this.comparisons() };
    const liveIds = new Set(this.siblingRelationships().map((rel) => rel.id));
    let changed = false;
    for (const id of Object.keys(current).map(Number)) {
      if (!liveIds.has(id)) {
        delete current[id];
        changed = true;
      }
    }
    for (const rel of this.siblingRelationships()) {
      if (rel.status !== 'CONFIRMED' || current[rel.id]) {
        continue;
      }
      current[rel.id] = { loading: true, error: '', result: null };
      changed = true;
      this.insightService.getSiblingComparison(
        this.projectId(), rel.datasetAId, rel.datasetBId,
      ).subscribe({
        next: (result) => this.comparisons.update((state) => ({
          ...state,
          [rel.id]: { loading: false, error: '', result },
        })),
        error: () => this.comparisons.update((state) => ({
          ...state,
          [rel.id]: {
            loading: false,
            error: 'Could not compute this comparison. Please try again later.',
            result: null,
          },
        })),
      });
    }
    if (changed) {
      this.comparisons.set(current);
    }
  }

  private syncFindings(): void {
    const current = { ...this.findings() };
    const liveIds = new Set(
      this.confirmedForeignKeyRelationships().map((rel) => rel.id));
    let changed = false;
    for (const id of Object.keys(current).map(Number)) {
      if (!liveIds.has(id)) {
        delete current[id];
        changed = true;
      }
    }
    for (const rel of this.confirmedForeignKeyRelationships()) {
      if (current[rel.id]) {
        continue;
      }
      current[rel.id] = { loading: true, error: '', result: null };
      changed = true;
      this.insightService.getRelationalInsights(this.projectId(), rel.id).subscribe({
        next: (result) => this.findings.update((state) => ({
          ...state,
          [rel.id]: { loading: false, error: '', result },
        })),
        error: () => this.findings.update((state) => ({
          ...state,
          [rel.id]: {
            loading: false,
            error: 'Could not compute cross-dataset findings. Please try again later.',
            result: null,
          },
        })),
      });
    }
    if (changed) {
      this.findings.set(current);
    }
  }
}
