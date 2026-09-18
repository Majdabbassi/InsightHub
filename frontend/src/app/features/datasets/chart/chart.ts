import { Component, ElementRef, Input, OnChanges, SimpleChanges, ViewChild, AfterViewInit } from '@angular/core';
import { Chart, ChartConfiguration, registerables } from 'chart.js';
import { applyChartTheme } from '../../../shared/chart-theme';

Chart.register(...registerables);

@Component({
  selector: 'app-chart',
  standalone: true,
  template: `<div class="chart-wrap"><canvas #canvas></canvas></div>`,
  styles: [`
    .chart-wrap { position: relative; height: 260px; }
    canvas { width: 100% !important; height: 100% !important; }
  `],
})
export class ChartComponent implements AfterViewInit, OnChanges {
  @Input() config!: ChartConfiguration;
  @ViewChild('canvas', { static: true }) canvasRef!: ElementRef<HTMLCanvasElement>;

  private chart: Chart | null = null;

  ngAfterViewInit(): void {
    this.renderChart();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['config'] && this.canvasRef) {
      this.renderChart();
    }
  }

  private renderChart(): void {
    if (!this.config || !this.canvasRef) return;
    if (this.chart) {
      this.chart.destroy();
    }
    this.chart = new Chart(
      this.canvasRef.nativeElement,
      applyChartTheme(this.config),
    );
  }
}
