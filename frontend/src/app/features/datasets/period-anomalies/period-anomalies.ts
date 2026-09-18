import { Component, OnInit, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { AnomalyDriver, PeriodAnomaly } from '../../../core/models/insight.model';
import { InsightService } from '../../../core/services/insight.service';

@Component({
  selector: 'app-period-anomalies',
  imports: [],
  templateUrl: './period-anomalies.html',
  styleUrl: './period-anomalies.scss',
})
export class PeriodAnomalies implements OnInit {
  private readonly insightService = inject(InsightService);

  readonly projectId = input.required<number>();
  readonly datasetId = input.required<number>();

  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly anomalies = signal<PeriodAnomaly[]>([]);
  readonly skippedReasons = signal<string[]>([]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.insightService.getAnomalies(this.projectId(), this.datasetId()).subscribe({
      next: (response) => {
        this.anomalies.set(response.anomalies);
        this.skippedReasons.set(response.skippedReasons);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        if (error.status === 409) {
          this.errorMessage.set('Run the analysis first — anomaly detection needs to know which columns are dates and which are numbers.');
        } else if (error.status === 503) {
          this.errorMessage.set('The analytics service is currently unavailable. Please try again later.');
        } else if (error.status === 400 || error.status === 422) {
          const detail = error.error?.message ?? error.error?.detail;
          this.errorMessage.set(
            typeof detail === 'string' && detail ? detail : 'Invalid anomaly detection request.',
          );
        } else {
          this.errorMessage.set('Could not run anomaly detection on this dataset.');
        }
      },
    });
  }

  formatNumber(value: number): string {
    return value.toLocaleString('en-US', { maximumFractionDigits: 2 });
  }

  formatMultiplier(multiplier: number | null): string {
    if (multiplier === null) {
      return 'n/a';
    }
    return `${multiplier}x normal`;
  }

  isSpike(direction: string): boolean {
    return direction === 'SPIKE';
  }

  driverShift(driver: AnomalyDriver): number {
    return driver.periodValue - driver.typicalValue;
  }

  driverWidth(driver: AnomalyDriver, drivers: AnomalyDriver[]): number {
    const max = Math.max(...drivers.map((d) => Math.abs(d.periodValue - d.typicalValue)), 1);
    return Math.round((Math.abs(this.driverShift(driver)) / max) * 100);
  }
}
