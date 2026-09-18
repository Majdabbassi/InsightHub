import { DatePipe } from '@angular/common';
import { Component, computed, input, output, signal } from '@angular/core';
import {
  AnalysisColumnStat,
  AnalysisResult,
  SemanticRole,
} from '../../../../core/models/analysis.model';
import { formatFractionPercent, formatNumber, truncate } from '../../../../shared/format';
import type { AnalysisState } from '../dataset-viewer.types';

const ROLE_LABELS: Record<SemanticRole, string> = {
  IDENTIFIER: 'Identifier',
  BOOLEAN: 'Boolean',
  TEMPORAL: 'Date',
  FREE_TEXT: 'Free text',
  CATEGORICAL: 'Categorical',
  NUMERIC_DISCRETE: 'Numeric',
  NUMERIC_CONTINUOUS: 'Numeric',
  CONSTANT: 'Constant',
  EMPTY: 'Empty',
  INCONSISTENT: 'Inconsistent',
};

const ROLE_WARNINGS: Partial<Record<SemanticRole, string>> = {
  INCONSISTENT:
    'This column mixes incompatible value types (e.g. numbers and text). Consider cleaning it.',
  CONSTANT: 'Every row holds the same value — this column carries no information.',
  EMPTY: 'This column contains no data at all.',
};

/** Rendering of the Quality tab: data-quality score, per-column analysis and
 *  correlations. Purely presentational - the viewer owns the analysis state
 *  and DSL calls, this component only asks for an analysis run via `analyze`. */
@Component({
  selector: 'app-quality-analysis',
  imports: [DatePipe],
  templateUrl: './quality-analysis.html',
  styleUrl: './quality-analysis.scss',
})
export class QualityAnalysis {
  readonly state = input<AnalysisState>('loading');
  readonly analysis = input<AnalysisResult | null>(null);
  readonly error = input('');

  readonly analyze = output<void>();

  readonly expandedColumns = signal<Set<string>>(new Set());
  readonly isExpanded = computed(() => (name: string) => this.expandedColumns().has(name));

  toggleColumnDetails(name: string): void {
    this.expandedColumns.update((current) => {
      const next = new Set(current);
      if (next.has(name)) {
        next.delete(name);
      } else {
        next.add(name);
      }
      return next;
    });
  }

  formatNumber(value: number | null | undefined): string {
    return formatNumber(value);
  }

  isNumericColumn(column: AnalysisColumnStat): boolean {
    if (column.semanticRole) {
      return (
        column.semanticRole === 'NUMERIC_DISCRETE' ||
        column.semanticRole === 'NUMERIC_CONTINUOUS'
      );
    }
    return column.dataType === 'integer' || column.dataType === 'float';
  }

  topValuesText(column: AnalysisColumnStat): string {
    return (column.topValues ?? [])
      .map((tv) => `${truncate(tv.value, 20)} (${tv.count})`)
      .join(', ');
  }

  roleLabel(column: AnalysisColumnStat): string {
    return column.semanticRole
      ? (ROLE_LABELS[column.semanticRole] ?? column.semanticRole)
      : '';
  }

  hasOutliers(column: AnalysisColumnStat): boolean {
    return !!column.outlierAnalysis && column.outlierAnalysis.outlierCount > 0;
  }

  strengthLabel(strength: string): string {
    const labels: Record<string, string> = {
      MODERATE: 'Moderate',
      STRONG: 'Strong',
      VERY_STRONG: 'Very strong',
    };
    return labels[strength] ?? strength;
  }

  gradeTier(grade: string): 'good' | 'fair' | 'poor' {
    if (grade === 'A' || grade === 'B') {
      return 'good';
    }
    return grade === 'C' ? 'fair' : 'poor';
  }

  outlierSummaryText(column: AnalysisColumnStat): string {
    const oa = column.outlierAnalysis;
    if (!oa || oa.outlierCount === 0) {
      return '';
    }
    const plural = oa.outlierCount === 1 ? 'outlier' : 'outliers';
    return oa.extremeCount > 0
      ? `${oa.outlierCount} ${plural} (${oa.extremeCount} extreme)`
      : `${oa.outlierCount} ${plural}`;
  }

  roleClass(column: AnalysisColumnStat): string {
    return column.semanticRole ? column.semanticRole.toLowerCase() : '';
  }

  needsAttention(column: AnalysisColumnStat): boolean {
    return !!column.semanticRole && ROLE_WARNINGS[column.semanticRole] !== undefined;
  }

  roleWarning(column: AnalysisColumnStat): string {
    return column.semanticRole ? (ROLE_WARNINGS[column.semanticRole] ?? '') : '';
  }

  confidencePercent(column: AnalysisColumnStat): string {
    return formatFractionPercent(column.confidence);
  }

  invalidValuesText(column: AnalysisColumnStat): string {
    return `${column.invalidValueCount ?? 0} value(s) did not match this column's detected type.`;
  }

  breakdownPercent(value: number): number {
    return Math.round(value * 100);
  }
}