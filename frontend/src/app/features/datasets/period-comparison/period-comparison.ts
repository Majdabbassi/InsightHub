import { Component, OnInit, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ComparisonDriver, PeriodComparisonResponse } from '../../../core/models/insight.model';
import { InsightService, PeriodComparisonParams } from '../../../core/services/insight.service';
import { formatNumber as sharedFormatNumber } from '../../../shared/format';

@Component({
  selector: 'app-period-comparison',
  imports: [],
  templateUrl: './period-comparison.html',
  styleUrl: './period-comparison.scss',
})
export class PeriodComparison implements OnInit {
  private readonly insightService = inject(InsightService);

  readonly projectId = input.required<number>();
  readonly datasetId = input.required<number>();

  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly result = signal<PeriodComparisonResponse | null>(null);

  readonly customOpen = signal(false);
  readonly currentStart = signal('');
  readonly currentEnd = signal('');
  readonly previousStart = signal('');
  readonly previousEnd = signal('');

  ngOnInit(): void {
    this.load();
  }

  load(params: PeriodComparisonParams = {}): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.insightService.getPeriodComparison(this.projectId(), this.datasetId(), params).subscribe({
      next: (response) => {
        this.result.set(response);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        if (error.status === 409) {
          this.errorMessage.set('Run the analysis first — comparisons need to know which columns are dates and which are numbers.');
        } else if (error.status === 503) {
          this.errorMessage.set('The analytics service is currently unavailable. Please try again later.');
        } else if (error.status === 400 || error.status === 422) {
          const detail = error.error?.message ?? error.error?.detail;
          this.errorMessage.set(
            typeof detail === 'string' && detail ? detail : 'Invalid comparison request.',
          );
        } else {
          this.errorMessage.set('Could not build a period comparison for this dataset.');
        }
      },
    });
  }

  toggleCustom(): void {
    this.customOpen.update((open) => !open);
  }

  onDateChange(field: 'currentStart' | 'currentEnd' | 'previousStart' | 'previousEnd', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    if (field === 'currentStart') this.currentStart.set(value);
    else if (field === 'currentEnd') this.currentEnd.set(value);
    else if (field === 'previousStart') this.previousStart.set(value);
    else this.previousEnd.set(value);
  }

  applyCustom(): void {
    const params: PeriodComparisonParams = {
      customCurrentStart: this.currentStart(),
      customCurrentEnd: this.currentEnd(),
      customPreviousStart: this.previousStart(),
      customPreviousEnd: this.previousEnd(),
    };
    if (!params.customCurrentStart || !params.customCurrentEnd
      || !params.customPreviousStart || !params.customPreviousEnd) {
      this.errorMessage.set('Pick all four dates to compare two custom ranges.');
      return;
    }
    this.load(params);
  }

  useAutomatic(): void {
    this.currentStart.set('');
    this.currentEnd.set('');
    this.previousStart.set('');
    this.previousEnd.set('');
    this.load();
  }

  formatNumber(value: number): string {
    return sharedFormatNumber(value, { maximumFractionDigits: 2 });
  }

  formatPercent(percentChange: number | null): string {
    if (percentChange === null) {
      return 'n/a';
    }
    return `${percentChange > 0 ? '+' : ''}${percentChange}%`;
  }

  formatDelta(delta: number): string {
    return `${delta > 0 ? '+' : ''}${this.formatNumber(delta)}`;
  }

  isIncrease(delta: number): boolean {
    return delta >= 0;
  }

  driverWidth(driver: ComparisonDriver, drivers: ComparisonDriver[]): number {
    const max = Math.max(...drivers.map((d) => Math.abs(d.delta)), 1);
    return Math.round((Math.abs(driver.delta) / max) * 100);
  }
}
