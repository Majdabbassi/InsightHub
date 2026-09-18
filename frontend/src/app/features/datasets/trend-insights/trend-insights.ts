import { Component, OnInit, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ChartConfiguration } from 'chart.js';
import { ChartComponent } from '../chart/chart';
import { TrendDirection, TrendInsight, TrendInsightsResponse } from '../../../core/models/insight.model';
import { InsightService } from '../../../core/services/insight.service';

const DIRECTION_ICONS: Record<TrendDirection, string> = {
  INCREASING: '↑',
  DECREASING: '↓',
  STABLE: '→',
};

@Component({
  selector: 'app-trend-insights',
  imports: [ChartComponent],
  templateUrl: './trend-insights.html',
  styleUrl: './trend-insights.scss',
})
export class TrendInsights implements OnInit {
  private readonly insightService = inject(InsightService);

  readonly projectId = input.required<number>();
  readonly datasetId = input.required<number>();

  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly result = signal<TrendInsightsResponse | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.insightService.getTrends(this.projectId(), this.datasetId()).subscribe({
      next: (response) => {
        this.result.set(response);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        if (error.status === 409) {
          this.errorMessage.set('Run the analysis first — trends need to know which columns are dates and which are numbers.');
        } else if (error.status === 503) {
          this.errorMessage.set('The analytics service is currently unavailable. Please try again later.');
        } else {
          this.errorMessage.set('Could not detect trends for this dataset.');
        }
      },
    });
  }

  iconFor(direction: TrendDirection): string {
    return DIRECTION_ICONS[direction];
  }

  sparkline(trend: TrendInsight): ChartConfiguration<'line'> {
    const colors: Record<TrendDirection, { line: string; fill: string }> = {
  INCREASING: { line: '#34d399', fill: 'rgba(52, 211, 153, 0.14)' },
  DECREASING: { line: '#f87171', fill: 'rgba(248, 113, 113, 0.13)' },
  STABLE: { line: '#7d8b9d', fill: 'rgba(125, 139, 157, 0.14)' },
    };
    const color = colors[trend.direction];

    return {
      type: 'line',
      data: {
        labels: trend.pointLabels,
        datasets: [
          {
            data: trend.points,
            borderColor: color.line,
            backgroundColor: color.fill,
            borderWidth: 2,
            tension: 0.3,
            pointRadius: 2,
            pointHoverRadius: 4,
            fill: true,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { display: false },
          y: { display: false },
        },
      },
    };
  }

  formatChange(percentageChange: number | null): string {
    if (percentageChange === null) {
      return 'change n/a (zero baseline)';
    }
    return `${percentageChange > 0 ? '+' : ''}${percentageChange}%`;
  }
}
