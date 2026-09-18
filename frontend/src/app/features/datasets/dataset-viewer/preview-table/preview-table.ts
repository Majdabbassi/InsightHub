import { Component, input, output } from '@angular/core';
import { CsvPreview } from '../../../../core/models/dataset.model';

/**
 * Read-only CSV preview table with a row-count picker. Pure presentation:
 * the parent owns loading/data state and reacts to `rowsChange`.
 */
@Component({
  selector: 'app-preview-table',
  imports: [],
  templateUrl: './preview-table.html',
  styleUrl: './preview-table.scss',
})
export class PreviewTable {
  readonly preview = input<CsvPreview | null>(null);
  readonly rowCount = input<number | null>(null);
  readonly loading = input(false);
  readonly rows = input(50);
  readonly rowOptions = input<number[]>([10, 25, 50, 100]);

  readonly rowsChange = output<number>();

  onRowsChange(event: Event): void {
    const value = Number((event.target as HTMLSelectElement).value);
    if (value && value !== this.rows()) {
      this.rowsChange.emit(value);
    }
  }
}