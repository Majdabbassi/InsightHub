import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Component, inject, output, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { DatasetService } from '../../../core/services/dataset.service';
import { formatBytes as sharedFormatBytes } from '../../../shared/format';

export const MAX_DATASET_SIZE_BYTES = 20 * 1024 * 1024;

@Component({
  selector: 'app-dataset-upload',
  imports: [],
  templateUrl: './dataset-upload.html',
  styleUrl: './dataset-upload.scss',
})
export class DatasetUpload {
  private readonly datasetService = inject(DatasetService);
  private readonly route = inject(ActivatedRoute);

  /** Emitted after a file uploaded successfully so hosts can refresh data. */
  readonly uploaded = output<void>();

  readonly uploading = signal(false);
  readonly progress = signal(0);
  readonly errorMessage = signal('');
  readonly dragOver = signal(false);

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) {
      this.upload(file);
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.uploading()) {
      this.dragOver.set(true);
    }
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.upload(file);
    }
  }

  private upload(file: File): void {
    if (this.uploading()) return;

    if (!file.name.toLowerCase().endsWith('.csv')) {
      this.errorMessage.set('Only .csv files are allowed.');
      return;
    }
    if (file.size === 0) {
      this.errorMessage.set('The selected file is empty.');
      return;
    }
    if (file.size > MAX_DATASET_SIZE_BYTES) {
      this.errorMessage.set(
        `File is too large (${this.formatSize(file.size)}). Maximum size is 20 MB.`
      );
      return;
    }

    const projectId = Number(this.route.snapshot.paramMap.get('id'));

    this.errorMessage.set('');
    this.uploading.set(true);
    this.progress.set(0);

    this.datasetService.uploadDataset(projectId, file).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.progress.set(Math.round((100 * event.loaded) / event.total));
        } else if (event.type === HttpEventType.Response && event.body) {
          this.datasetService.addDatasetToState(event.body);
          this.uploading.set(false);
          this.progress.set(100);
          this.uploaded.emit();
        }
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage.set(err.error?.message ?? 'Upload failed. Please try again.');
        this.uploading.set(false);
      },
    });
  }

  formatSize(bytes: number): string {
    return sharedFormatBytes(bytes);
  }
}
