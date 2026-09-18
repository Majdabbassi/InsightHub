import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

type IconPaths = string[];

const ICONS: Record<string, IconPaths> = {
  dashboard: [
    'M3 3h7v9H3z',
    'M14 3h7v5h-7z',
    'M14 12h7v9h-7z',
    'M3 16h7v5H3z',
  ],
  folder: ['M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z'],
  file: [
    'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z',
    'M14 2v6h6',
  ],
  upload: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'M17 8l-5-5-5 5', 'M12 3v12'],
  download: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'M7 10l5 5 5-5', 'M12 15V3'],
  chart: ['M18 20V10', 'M12 20V4', 'M6 20v-6'],
  activity: ['M22 12h-4l-3 9L9 3l-3 9H2'],
  sparkles: [
    'M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z',
    'M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9z',
  ],
  broom: ['M19 21l-7-7', 'M13 8l-8.5 8.5a2.1 2.1 0 0 0 3 3L16 11', 'M11 6l4-4 6 6-4 4'],
  alert: ['M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z', 'M12 9v4', 'M12 17h.01'],
  check: ['M22 11.1V12a10 10 0 1 1-5.9-9.1', 'M22 4L12 14l-3-3'],
  x: ['M18 6L6 18', 'M6 6l12 12'],
  trophy: [
    'M8 21h8',
    'M12 17v4',
    'M7 4h10v5a5 5 0 0 1-10 0z',
    'M7 6H4a2 2 0 0 0 2 4h1',
    'M17 6h3a2 2 0 0 1-2 4h-1',
  ],
  arrowUp: ['M12 19V5', 'M5 12l7-7 7 7'],
  arrowDown: ['M12 5v14', 'M19 12l-7 7-7-7'],
  zap: ['M13 2L3 14h9l-1 8 10-12h-9z'],
  table: [
    'M3 5h18v14H3z',
    'M3 10h18',
    'M3 15h18',
    'M9 5v14',
  ],
  edit: ['M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7', 'M18.5 2.5a2.1 2.1 0 0 1 3 3L12 15l-4 1 1-4z'],
  trash: ['M3 6h18', 'M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2', 'M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6'],
  plus: ['M12 5v14', 'M5 12h14'],
  search: ['M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z', 'M21 21l-4.3-4.3'],
  chevronLeft: ['M15 18l-6-6 6-6'],
  chevronRight: ['M9 18l6-6-6-6'],
  chevronsLeft: ['M11 17l-5-5 5-5', 'M18 17l-5-5 5-5'],
  menu: ['M3 6h18', 'M3 12h18', 'M3 18h18'],
  user: ['M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2', 'M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z'],
  logout: ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'],
  refresh: ['M23 4v6h-6', 'M1 20v-6h6', 'M3.5 9a9 9 0 0 1 14.9-3.4L23 10', 'M1 14l4.6 4.4A9 9 0 0 0 20.5 15'],
  play: ['M5 3l14 9-14 9z'],
  network: [
    'M12 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'M5 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'M19 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'M12 9v4m0 0l-5.5 4M12 13l5.5 4',
  ],
  eye: [
    'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z',
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 8z',
  ],
  copy: [
    'M20 9h-9a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2z',
    'M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1',
  ],
};

@Component({
  selector: 'ui-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="size"
      [attr.height]="size"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true">
      @for (d of paths(); track $index) {
        <path [attr.d]="d" />
      }
    </svg>
  `,
  styles: [
    `:host { display: inline-flex; line-height: 0; flex-shrink: 0; }`,
  ],
})
export class Icon {
  @Input({ required: true }) name!: string;
  @Input() size = 16;

  paths(): IconPaths {
    return ICONS[this.name] ?? ICONS['activity'];
  }
}
