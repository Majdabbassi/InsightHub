import { DatePipe, DecimalPipe, NgClass } from '@angular/common';
import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatasetUpload } from '../../datasets/dataset-upload/dataset-upload';
import { OverviewDataset, OverviewStats } from '../../../core/models/project-overview.model';
import { ProjectOverviewService } from '../../../core/services/project-overview.service';
import { RelationshipService } from '../../../core/services/relationship.service';
import { DatasetService } from '../../../core/services/dataset.service';
import { RelationshipDiagram } from '../relationship-diagram/relationship-diagram';
import { ProjectInsights } from '../project-insights/project-insights';
import { ProjectChat } from '../project-chat/project-chat';
import { Project } from '../../../core/models/project.model';
import { ProjectService } from '../../../core/services/project.service';

@Component({
  selector: 'app-project-detail',
  imports: [DatePipe, DecimalPipe, NgClass, RouterLink, DatasetUpload, RelationshipDiagram, ProjectInsights, ProjectChat],
  templateUrl: './project-detail.html',
  styleUrl: './project-detail.scss',
})
export class ProjectDetail implements OnInit {
  private readonly projectService = inject(ProjectService);
  private readonly overviewService = inject(ProjectOverviewService);
  private readonly relationshipService = inject(RelationshipService);
  private readonly datasetService = inject(DatasetService);
  private readonly route = inject(ActivatedRoute);

  readonly projectId = Number(this.route.snapshot.paramMap.get('id'));

  readonly project = signal<Project | null>(null);
  readonly loading = signal(true);
  readonly errorMessage = signal('');

  /** false (default) = active datasets only; true = every version. */
  readonly showAllVersions = signal(false);

  readonly overview = this.overviewService.overview;
  readonly datasetPendingDelete = signal<OverviewDataset | null>(null);
  readonly deleting = signal(false);
  readonly deleteError = signal('');

  readonly stats = computed<OverviewStats | null>(() => {
    const overview = this.overview();
    if (!overview) {
      return null;
    }
    return this.showAllVersions() ? overview.allVersionsStats : overview.activeStats;
  });

  readonly displayedDatasets = computed<OverviewDataset[]>(() => {
    const overview = this.overview();
    if (!overview) {
      return [];
    }
    return this.showAllVersions()
      ? overview.datasets
      : overview.datasets.filter((dataset) => dataset.isActive);
  });

  private readonly datasetNameById = computed(() => {
    const byId = new Map<number, string>();
    for (const dataset of this.overview()?.datasets ?? []) {
      byId.set(dataset.id, dataset.name);
    }
    return byId;
  });

  /** The dataset that superseded each given one: the first dataset cleaned
      from it (directly or as the next link in the chain original → c1 → c2). */
  private readonly supersededIntoIdBySource = computed(() => {
    const next = new Map<number, number>();
    for (const dataset of this.overview()?.datasets ?? []) {
      const source = dataset.sourceDatasetId;
      if (source !== null && !next.has(source)) {
        next.set(source, dataset.id);
      }
    }
    return next;
  });

  ngOnInit(): void {
    this.loadProject();
    this.loadOverview();
  }

  toggleVersions(checked: boolean): void {
    this.showAllVersions.set(checked);
  }

  supersededInto(dataset: OverviewDataset): string | null {
    if (dataset.isActive) {
      return null;
    }
    // Every inactive dataset is superseded by whatever was cleaned from it
    // (its next descendant in the cleaning chain), pointing forward.
    const nextId = this.supersededIntoIdBySource().get(dataset.id);
    return nextId === undefined ? null : this.datasetNameById().get(nextId) ?? null;
  }

  gradeBreakdownEntries(stats: OverviewStats | null): { grade: string; count: number }[] {
    if (!stats?.qualityGradeBreakdown) {
      return [];
    }
    return Object.entries(stats.qualityGradeBreakdown)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([grade, count]) => ({ grade, count }));
  }

  formatAverageScore(score: number | null): string {
    if (score === null) {
      return '—';
    }
    return Number.isInteger(score) ? String(score) : score.toFixed(1);
  }

  gradeLetter(score: number): string {
    if (score >= 90) {
      return 'A';
    }
    if (score >= 80) {
      return 'B';
    }
    if (score >= 70) {
      return 'C';
    }
    if (score >= 60) {
      return 'D';
    }
    return 'F';
  }

  requestDelete(dataset: OverviewDataset): void {
    this.deleteError.set('');
    this.datasetPendingDelete.set(dataset);
  }

  cancelDelete(): void {
    this.datasetPendingDelete.set(null);
  }

  confirmDelete(): void {
    const dataset = this.datasetPendingDelete();
    if (!dataset) return;

    this.deleting.set(true);
    this.datasetService.deleteDataset(this.projectId, dataset.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.datasetPendingDelete.set(null);
        this.loadOverview();
      },
      error: () => {
        this.deleting.set(false);
        this.datasetPendingDelete.set(null);
        this.deleteError.set('Could not delete the dataset. Please try again.');
      },
    });
  }

  private loadProject(): void {
    this.projectService.getProject(this.projectId).subscribe({
      next: (project) => this.project.set(project),
      error: () => {
        this.errorMessage.set(
          'Could not load this project. It may not exist or you may not have access.'
        );
        this.loading.set(false);
      },
    });
  }

  loadOverview(): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.overviewService.clear();
    this.relationshipService.clear();
    this.overviewService.getProjectOverview(this.projectId).subscribe({
      next: () => {
        this.relationshipService.getRelationships(this.projectId).subscribe({
          next: () => this.loading.set(false),
          error: () => this.loading.set(false),
        });
      },
      error: () => {
        this.errorMessage.set(
          'Could not load the project overview. It may not exist or you may not have access.'
        );
        this.loading.set(false);
      },
    });
  }
}
