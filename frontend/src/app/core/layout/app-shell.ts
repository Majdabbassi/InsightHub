import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { ProjectService } from '../services/project.service';
import { DatasetService } from '../services/dataset.service';
import { Icon } from '../../shared/icons/icon';

const NAV_COLLAPSED_KEY = 'da_nav_collapsed';

@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon],
  templateUrl: './app-shell.html',
  styleUrl: './app-shell.scss',
})
export class AppShell implements OnInit, OnDestroy {
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);
  private readonly projectService = inject(ProjectService);
  private readonly datasetService = inject(DatasetService);

  readonly user = this.authService.currentUser;
  readonly collapsed = signal(localStorage.getItem(NAV_COLLAPSED_KEY) === '1');

  readonly projectId = signal<number | null>(null);
  readonly projectName = signal('');
  readonly datasetId = signal<number | null>(null);
  readonly datasetName = signal('');

  readonly initial = computed(() => {
    const name = this.user()?.fullName ?? '';
    return name ? name.charAt(0).toUpperCase() : '?';
  });

  private routerSub?: Subscription;
  private readonly projectNameCache = new Map<number, string>();
  private readonly datasetNameCache = new Map<string, string>();

  readonly mobileOpen = signal(false);

  ngOnInit(): void {
    this.parseUrl(this.router.url);
    this.routerSub = this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => {
        this.parseUrl(event.urlAfterRedirects);
        // Close the mobile drawer whenever navigation completes.
        this.mobileOpen.set(false);
      });
  }

  ngOnDestroy(): void {
    this.routerSub?.unsubscribe();
  }

  toggleCollapsed(): void {
    const next = !this.collapsed();
    this.collapsed.set(next);
    localStorage.setItem(NAV_COLLAPSED_KEY, next ? '1' : '0');
  }

  toggleMobile(): void {
    this.mobileOpen.set(!this.mobileOpen());
  }

  signOut(): void {
    this.authService.logout();
    void this.router.navigate(['/login']);
  }

  private parseUrl(url: string): void {
    const segments = url.split('?')[0].split('/').filter(Boolean);

    if (segments[0] !== 'projects' || !segments[1]) {
      this.projectId.set(null);
      this.projectName.set('');
      this.datasetId.set(null);
      this.datasetName.set('');
      return;
    }

    const projectId = Number(segments[1]);
    const datasetId = segments[2] === 'datasets' && segments[3] ? Number(segments[3]) : null;

    this.projectId.set(projectId);
    this.resolveProjectName(projectId);

    if (datasetId) {
      this.datasetId.set(datasetId);
      this.resolveDatasetName(projectId, datasetId);
    } else {
      this.datasetId.set(null);
      this.datasetName.set('');
    }
  }

  private resolveProjectName(projectId: number): void {
    const cached = this.projectNameCache.get(projectId);
    if (cached) {
      this.projectName.set(cached);
      return;
    }
    const known = this.projectService.projects().find((p) => p.id === projectId);
    if (known?.name) {
      this.projectNameCache.set(projectId, known.name);
      this.projectName.set(known.name);
      return;
    }
    this.projectService.getProject(projectId).subscribe({
      next: (project) => {
        this.projectNameCache.set(projectId, project.name);
        if (this.projectId() === projectId) this.projectName.set(project.name);
      },
      error: () => {
        if (this.projectId() === projectId && !this.projectName()) {
          this.projectName.set(`Project #${projectId}`);
        }
      },
    });
  }

  private resolveDatasetName(projectId: number, datasetId: number): void {
    const key = `${projectId}:${datasetId}`;
    const cached = this.datasetNameCache.get(key);
    if (cached) {
      this.datasetName.set(cached);
      return;
    }
    this.datasetService.getDataset(projectId, datasetId).subscribe({
      next: (dataset) => {
        this.datasetNameCache.set(key, dataset.originalFilename);
        if (this.datasetId() === datasetId) this.datasetName.set(dataset.originalFilename);
      },
      error: () => {
        if (this.datasetId() === datasetId && !this.datasetName()) {
          this.datasetName.set(`Dataset #${datasetId}`);
        }
      },
    });
  }
}

