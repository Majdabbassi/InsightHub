import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Project } from '../../../core/models/project.model';
import { ProjectService } from '../../../core/services/project.service';

@Component({
  selector: 'app-project-list',
  imports: [DatePipe, RouterLink],
  templateUrl: './project-list.html',
  styleUrl: './project-list.scss',
})
export class ProjectList {
  private readonly projectService = inject(ProjectService);
  private readonly router = inject(Router);

  readonly projects = this.projectService.projects;
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly projectPendingDelete = signal<Project | null>(null);
  readonly deleting = signal(false);

  ngOnInit(): void {
    this.loadProjects();
  }

  loadProjects(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.projectService.getProjects().subscribe({
      next: () => this.loading.set(false),
      error: () => {
        this.errorMessage.set('Could not load your projects. Please try again.');
        this.loading.set(false);
      },
    });
  }

  open(project: Project): void {
    this.router.navigate(['/projects', project.id]);
  }

  requestDelete(event: Event, project: Project): void {
    event.stopPropagation();
    this.projectPendingDelete.set(project);
  }

  cancelDelete(): void {
    this.projectPendingDelete.set(null);
  }

  confirmDelete(): void {
    const project = this.projectPendingDelete();
    if (!project) return;

    this.deleting.set(true);
    this.projectService.deleteProject(project.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.projectPendingDelete.set(null);
      },
      error: () => {
        this.deleting.set(false);
        this.projectPendingDelete.set(null);
        this.errorMessage.set('Could not delete the project. Please try again.');
      },
    });
  }
}
