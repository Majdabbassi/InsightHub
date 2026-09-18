import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ChartConfiguration } from 'chart.js';
import { ChartComponent } from '../chart/chart';
import { CHART_ACCENT, chartColor, chartFill } from '../../../shared/chart-theme';
import {
  DashboardResponse,
  ChartSuggestion,
  ChartDataResponse,
} from '../../../core/models/dashboard.model';
import { Dataset } from '../../../core/models/dataset.model';
import { DatasetService } from '../../../core/services/dataset.service';
import { Icon } from '../../../shared/icons/icon';
import {
  formatNumber as sharedFormatNumber,
  formatPercent as sharedFormatPercent,
} from '../../../shared/format';

interface ChartState {
  loading: boolean;
  error: string;
  config: ChartConfiguration | null;
}

@Component({
  selector: 'app-dataset-dashboard',
  imports: [RouterLink, ChartComponent, Icon],
  templateUrl: './dataset-dashboard.html',
  styleUrl: './dataset-dashboard.scss',
})
export class DatasetDashboard implements OnInit {
  private readonly datasetService = inject(DatasetService);
  private readonly route = inject(ActivatedRoute);

  /** When true (embedded in the dataset workspace), back links are hidden. */
  @Input() embedded = false;

  projectIdValue = 0;
  datasetIdValue = 0;

  readonly dataset = signal<Dataset | null>(null);
  readonly dashboard = signal<DashboardResponse | null>(null);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly showAllCharts = signal(false);

  /** Featured charts are shown by default; the rest hide behind a toggle. */
  readonly featuredCharts = computed(() =>
    (this.dashboard()?.suggestedCharts ?? []).filter((c) => c.featured)
  );
  readonly moreCharts = computed(() =>
    (this.dashboard()?.suggestedCharts ?? []).filter((c) => !c.featured)
  );

  private loadedChartIds = new Set<string>();

  readonly chartStates = signal<Record<string, ChartState>>({});

  ngOnInit(): void {
    this.route.paramMap.subscribe((params) => {
      const projectId = Number(params.get('id'));
      const datasetId = Number(params.get('datasetId'));

      if (!projectId || !datasetId) {
        this.errorMessage.set('Invalid dashboard link.');
        this.loading.set(false);
        return;
      }

      if (projectId === this.projectIdValue && datasetId === this.datasetIdValue) {
        return;
      }

      this.projectIdValue = projectId;
      this.datasetIdValue = datasetId;
      this.loadedChartIds.clear();
      this.loadDashboard();
    });
  }

  private loadDashboard(): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.showAllCharts.set(false);
    this.loadedChartIds.clear();

    this.datasetService.getDataset(this.projectIdValue, this.datasetIdValue).subscribe({
      next: (dataset) => {
        this.dataset.set(dataset);
        this.datasetService.getDashboard(this.projectIdValue, this.datasetIdValue).subscribe({
          next: (response) => {
            this.dashboard.set(response);
            this.loading.set(false);
            this.initChartStates(response.suggestedCharts);
            // Featured charts load eagerly; the rest wait for the expansion.
            for (const chart of response.suggestedCharts) {
              if (chart.featured) {
                this.loadChartData(chart);
              }
            }
          },
          error: (err: HttpErrorResponse) => {
            this.loading.set(false);
            if (err.status === 409) {
              this.errorMessage.set('Analyze this dataset first to see the dashboard.');
            } else {
              this.errorMessage.set(err.error?.message ?? 'Failed to load dashboard.');
            }
          },
        });
      },
      error: () => {
        this.errorMessage.set('Could not load dataset.');
        this.loading.set(false);
      },
    });
  }

  private initChartStates(charts: ChartSuggestion[]): void {
    const states: Record<string, ChartState> = {};
    for (const chart of charts) {
      states[chart.id] = { loading: false, error: '', config: null };
    }
    this.chartStates.set(states);
  }

  toggleShowAll(): void {
    const next = !this.showAllCharts();
    this.showAllCharts.set(next);
    if (next) {
      for (const chart of this.moreCharts()) {
        this.loadChartData(chart);
      }
    }
  }

  private loadChartData(chart: ChartSuggestion): void {
    if (this.loadedChartIds.has(chart.id)) {
      return;
    }
    this.loadedChartIds.add(chart.id);
    this.chartStates.update((current) => ({
      ...current,
      [chart.id]: { ...(current[chart.id] ?? { loading: false, error: '', config: null }), loading: true },
    }));

    this.datasetService
      .getChartData(
        this.projectIdValue,
        this.datasetIdValue,
        chart.xColumn,
        chart.yColumn,
        chart.type,
        chart.aggregation
      )
      .subscribe({
        next: (data) => {
          this.chartStates.update((current) => ({
            ...current,
            [chart.id]: {
              loading: false,
              error: '',
              config: this.buildChartConfig(chart, data),
            },
          }));
        },
        error: (err: HttpErrorResponse) => {
          this.chartStates.update((current) => ({
            ...current,
            [chart.id]: {
              loading: false,
              error: err.error?.message ?? 'Could not load chart data.',
              config: null,
            },
          }));
        },
      });
  }

  private buildChartConfig(
    chart: ChartSuggestion,
    data: ChartDataResponse
  ): ChartConfiguration {
    switch (chart.type) {
      case 'PIE':
        return this.pieConfig(chart, data) as ChartConfiguration;
      case 'LINE':
        return this.lineConfig(chart, data) as ChartConfiguration;
      case 'HISTOGRAM':
        return this.histogramConfig(chart, data) as ChartConfiguration;
      case 'SCATTER':
        return this.scatterConfig(chart, data) as ChartConfiguration;
      default:
        return this.barConfig(chart, data) as ChartConfiguration;
    }
  }

  private colorFor(index: number): string {
    return chartColor(index);
  }

  private pieConfig(chart: ChartSuggestion, data: ChartDataResponse): ChartConfiguration<'pie'> {
    return {
      type: 'pie',
      data: {
        labels: data.labels,
        datasets: [
          {
            label: chart.title,
            data: data.values,
            backgroundColor: data.labels.map((_, i) => this.colorFor(i)),
            borderWidth: 1,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: true, position: 'right' } },
      },
    };
  }

  private lineConfig(chart: ChartSuggestion, data: ChartDataResponse): ChartConfiguration<'line'> {
    return {
      type: 'line',
      data: {
        labels: data.labels,
        datasets: [
          {
            label: chart.title,
            data: data.values,
            backgroundColor: chartFill(0),
            borderColor: this.colorFor(0),
            borderWidth: 2,
            tension: 0.3,
            fill: true,
            pointRadius: 2,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { maxRotation: 45, autoSkip: true, font: { size: 11 } } },
          y: { beginAtZero: true },
        },
      },
    };
  }

  private barConfig(chart: ChartSuggestion, data: ChartDataResponse): ChartConfiguration<'bar'> {
    return {
      type: 'bar',
      data: {
        labels: data.labels,
        datasets: [
          {
            label: chart.title,
            data: data.values,
            backgroundColor: data.labels.map((_, i) =>
              chartFill(i, 0.75)
            ),
            borderColor: '#12171f',
            borderWidth: 1,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { maxRotation: 45, font: { size: 11 } } },
          y: { beginAtZero: true },
        },
      },
    };
  }

  /** Histograms render as gap-less bars so bins read as a continuous range. */
  private histogramConfig(
    chart: ChartSuggestion,
    data: ChartDataResponse
  ): ChartConfiguration<'bar'> {
    return {
      type: 'bar', // chart.js has no native histogram; rendered as touching bars
      data: {
        labels: data.labels,
        datasets: [
          {
            label: chart.title,
            data: data.values,
            backgroundColor: chartFill(0, 0.65),
            borderColor: CHART_ACCENT,
            borderWidth: 1,
            barPercentage: 1.0,
            categoryPercentage: 1.0,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { maxRotation: 60, minRotation: 0, autoSkip: false, font: { size: 9 } } },
          y: { beginAtZero: true },
        },
      },
    };
  }

  /** Scatter plots use raw (x, y) points instead of label/value arrays. */
  private scatterConfig(
    chart: ChartSuggestion,
    data: ChartDataResponse
  ): ChartConfiguration<'scatter'> {
    const points = (data.points ?? []).map((p) => ({ x: p.x, y: p.y }));
    return {
      type: 'scatter',
      data: {
        datasets: [
          {
            label: chart.title,
            data: points,
            backgroundColor: chartFill(0, 0.55),
            borderColor: CHART_ACCENT,
            pointRadius: 3,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { type: 'linear', title: { display: true, text: chart.xColumn } },
          y: {
            type: 'linear',
            beginAtZero: false,
            title: { display: !!chart.yColumn, text: chart.yColumn ?? '' },
          },
        },
      },
    };
  }

  formatNumber(value: number | null | undefined): string {
    return sharedFormatNumber(value);
  }

  formatPercent(value: number): string {
    return sharedFormatPercent(value);
  }
}
