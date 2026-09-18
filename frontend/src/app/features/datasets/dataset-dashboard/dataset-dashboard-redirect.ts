import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

/** Legacy /datasets/:id/dashboard URLs now land on the workspace's Explore tab. */
@Component({
  selector: 'app-dashboard-redirect',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
})
export class DatasetDashboardRedirect implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  ngOnInit(): void {
    void this.router.navigate(['..'], {
      relativeTo: this.route,
      queryParams: { tab: 'explore' },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
