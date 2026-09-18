import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ProjectService } from '../../../core/services/project.service';

@Component({
  selector: 'app-project-form',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './project-form.html',
  styleUrl: './project-form.scss',
})
export class ProjectForm implements OnInit {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly projectService = inject(ProjectService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly editing = signal(false);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal('');

  private projectId: number | null = null;

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: ['', [Validators.maxLength(1000)]],
  });

  ngOnInit(): void {
    const idParam = this.route.snapshot.paramMap.get('id');
    if (idParam) {
      this.editing.set(true);
      this.projectId = Number(idParam);
      this.loadProject(this.projectId);
    }
  }

  onSubmit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');

    const request = this.form.getRawValue();

    const call$ = this.editing()
      ? this.projectService.updateProject(this.projectId!, request)
      : this.projectService.createProject(request);

    call$.subscribe({
      next: (project) => this.router.navigate(['/projects', project.id]),
      error: () => {
        this.errorMessage.set('Could not save the project. Please try again.');
        this.saving.set(false);
      },
    });
  }

  private loadProject(id: number): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.projectService.getProject(id).subscribe({
      next: (project) => {
        this.form.patchValue({
          name: project.name,
          description: project.description ?? '',
        });
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('Could not load the project. It may have been deleted.');
        this.loading.set(false);
      },
    });
  }
}
