import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { ProjectService } from '../../core/services/project.service';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly projectService = inject(ProjectService);

  readonly user = this.authService.currentUser;
  readonly recentProjects = computed(() =>
    [...this.projectService.projects()].sort((a, b) => b.id - a.id).slice(0, 3)
  );
  readonly loadError = signal('');

  ngOnInit(): void {
    this.loadError.set('');
    this.projectService.getProjects().subscribe({
      error: () => this.loadError.set('Could not load your projects. Please try again later.'),
    });
  }
}
