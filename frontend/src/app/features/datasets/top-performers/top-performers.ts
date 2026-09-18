import { Component, OnInit, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { PerformerEntry, PerformersPair } from '../../../core/models/insight.model';
import { InsightService } from '../../../core/services/insight.service';

@Component({
  selector: 'app-top-performers',
  imports: [],
  templateUrl: './top-performers.html',
  styleUrl: './top-performers.scss',
})
export class TopPerformers implements OnInit {
  private readonly insightService = inject(InsightService);

  readonly projectId = input.required<number>();
  readonly datasetId = input.required<number>();

  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly pairs = signal<PerformersPair[]>([]);
  readonly skippedReasons = signal<string[]>([]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.insightService.getPerformers(this.projectId(), this.datasetId()).subscribe({
      next: (response) => {
        this.pairs.set(response.performers);
        this.skippedReasons.set(response.skippedReasons);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        if (error.status === 409) {
          this.errorMessage.set('Run the analysis first — performer rankings need to know which columns are categories and which are numbers.');
        } else if (error.status === 503) {
          this.errorMessage.set('The analytics service is currently unavailable. Please try again later.');
        } else if (error.status === 400 || error.status === 422) {
          const detail = error.error?.message ?? error.error?.detail;
          this.errorMessage.set(
            typeof detail === 'string' && detail ? detail : 'Invalid performer ranking request.',
          );
        } else {
          this.errorMessage.set('Could not rank performers on this dataset.');
        }
      },
    });
  }

  formatNumber(value: number): string {
    return value.toLocaleString('en-US', { maximumFractionDigits: 2 });
  }

  formatGap(gap: number | null): string {
    return gap === null ? 'n/a' : `${gap}x difference`;
  }

  entryWidth(entry: PerformerEntry, entries: PerformerEntry[]): number {
    const max = Math.max(...entries.map((e) => e.sumValue), 1);
    return Math.round((entry.sumValue / max) * 100);
  }
}
