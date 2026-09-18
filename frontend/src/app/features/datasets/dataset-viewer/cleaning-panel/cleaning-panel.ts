import { Component, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  CleanedDatasetResponse,
  CleaningActionType,
  CleaningSuggestion,
} from '../../../../core/models/cleaning.model';
import { DatasetService } from '../../../../core/services/dataset.service';
import type {
  ActionChangeEvent,
  CleaningSelection,
  CustomValueChangeEvent,
  ToggleSuggestionEvent,
} from '../dataset-viewer.types';

/**
 * The Clean tab: per-suggestion selection UI and result card. Pure
 * presentation over suggestions/selections owned by the viewer; every user
 * action is surfaced as an output event the parent resolves against state.
 */
@Component({
  selector: 'app-cleaning-panel',
  imports: [RouterLink],
  templateUrl: './cleaning-panel.html',
  styleUrl: './cleaning-panel.scss',
})
export class CleaningPanel {
  private readonly datasetService = inject(DatasetService);

  readonly projectId = input<number>(0);
  readonly open = input(false);
  readonly loading = input(false);
  readonly running = input(false);
  readonly error = input('');
  readonly suggestions = input<CleaningSuggestion[]>([]);
  readonly selections = input<Record<string, CleaningSelection>>({});
  readonly result = input<CleanedDatasetResponse | null>(null);

  readonly toggle = output<void>();
  readonly toggleSuggestion = output<ToggleSuggestionEvent>();
  readonly actionChange = output<ActionChangeEvent>();
  readonly customValueChange = output<CustomValueChangeEvent>();
  readonly apply = output<void>();

  enabledCount(): number {
    let count = 0;
    for (const selection of Object.values(this.selections())) {
      if (selection.enabled) count++;
    }
    return count;
  }

  needsCustomValue(selection: CleaningSelection): boolean {
    return selection.action === CleaningActionType.FILL_CUSTOM_VALUE;
  }

  cleaningActionLabel(action: CleaningActionType): string {
    return this.datasetService.cleaningActionLabel(action);
  }

  onToggleSuggestion(id: string, event: Event): void {
    this.toggleSuggestion.emit({
      id,
      checked: (event.target as HTMLInputElement).checked,
    });
  }

  onActionChange(id: string, event: Event): void {
    this.actionChange.emit({
      id,
      action: (event.target as HTMLSelectElement).value as CleaningActionType,
    });
  }

  onCustomValueChange(id: string, event: Event): void {
    this.customValueChange.emit({ id, value: (event.target as HTMLInputElement).value });
  }
}