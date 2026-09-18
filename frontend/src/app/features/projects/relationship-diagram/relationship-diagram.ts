import { DecimalPipe, NgClass } from '@angular/common';
import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OverviewDataset } from '../../../core/models/project-overview.model';
import { DatasetRelationship } from '../../../core/models/relationship.model';
import { RelationshipService } from '../../../core/services/relationship.service';

interface DiagramNode {
  id: number;
  name: string;
  rowCount: number;
  qualityGrade: string | null;
  x: number;
  y: number;
}

interface DiagramEdge {
  rel: DatasetRelationship;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  mx: number;
  my: number;
  statusClass: string;
}

interface DragState {
  nodeId: number;
  offsetX: number;
  offsetY: number;
  startX: number;
  startY: number;
  moved: boolean;
}

const NODE_W = 176;
const NODE_H = 80;
const CANVAS_W = 920;
const CANVAS_H = 520;
const EDGE_PAD = 14;

@Component({
  selector: 'app-relationship-diagram',
  imports: [FormsModule, NgClass, DecimalPipe],
  templateUrl: './relationship-diagram.html',
  styleUrl: './relationship-diagram.scss',
})
export class RelationshipDiagram {
  readonly projectId = input.required<number>();
  readonly datasets = input.required<OverviewDataset[]>();

  private readonly relationshipService = inject(RelationshipService);
  readonly relationships = this.relationshipService.relationships;

  private readonly canvasRef =
    viewChild.required<ElementRef<HTMLDivElement>>('canvas');

  readonly nodes = signal<DiagramNode[]>([]);
  readonly selectedNodeId = signal<number | null>(null);
  readonly selectedRelId = signal<number | null>(null);

  readonly scanning = signal(false);
  readonly scanMessage = signal('');
  readonly panelError = signal('');

  readonly addModalOpen = signal(false);
  readonly adding = signal(false);
  readonly addError = signal('');
  formDatasetAId = '';
  formDatasetBId = '';
  formColumnA = '';
  formColumnB = '';

  private dragState: DragState | null = null;
  private laidOutForIds = '';

  readonly visibleRelationships = computed(() =>
    this.relationships().filter((rel) => rel.status !== 'REJECTED'));

  readonly selectedRelationship = computed(() =>
    this.relationships().find((rel) => rel.id === this.selectedRelId()) ?? null);

  private readonly nodeById = computed(() => {
    const byId = new Map<number, DiagramNode>();
    for (const node of this.nodes()) {
      byId.set(node.id, node);
    }
    return byId;
  });

  readonly edges = computed<DiagramEdge[]>(() => {
    const byId = this.nodeById();
    const edges: DiagramEdge[] = [];
    for (const rel of this.visibleRelationships()) {
      const a = byId.get(rel.datasetAId);
      const b = byId.get(rel.datasetBId);
      if (!a || !b) {
        continue;
      }
      const line = anchor(a, b);
      edges.push({
        rel,
        ...line,
        ...midpoint(line.x1, line.y1, line.x2, line.y2),
        statusClass: edgeClass(rel),
      });
    }
    return edges;
  });

  readonly hasEnoughDatasets = computed(() => this.datasets().length >= 2);

  constructor() {
    effect(() => {
      const datasets = this.datasets();
      if (datasets.length === 0) {
        return;
      }
      const ids = datasets.map((dataset) => dataset.id).join(',');
      if (ids === this.laidOutForIds) {
        return;
      }
      this.laidOutForIds = ids;
      this.nodes.set(layoutNodes(datasets));
    });
  }

  edgeLabel(edge: DiagramEdge): string {
    const match = edge.rel.matchPercentage;
    return match === null
      ? `${edge.rel.sharedColumnA}`
      : `${edge.rel.sharedColumnA} · ${match}%`;
  }

  edgeTouches(rel: DatasetRelationship, nodeId: number | null): boolean {
    if (nodeId === null) {
      return false;
    }
    return rel.datasetAId === nodeId || rel.datasetBId === nodeId;
  }

  nodeIsDimmed(nodeId: number): boolean {
    const selected = this.selectedNodeId();
    if (selected === null) {
      return false;
    }
    return !this.visibleRelationships().some((rel) => this.edgeTouches(rel, nodeId));
  }

  onCanvasMouseDown(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.selectedNodeId.set(null);
      this.selectedRelId.set(null);
      this.panelError.set('');
    }
  }

  onCardMouseDown(event: MouseEvent, node: DiagramNode): void {
    if (event.button !== 0) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    const rect = this.canvasRef().nativeElement.getBoundingClientRect();
    this.dragState = {
      nodeId: node.id,
      offsetX: event.clientX - rect.left - node.x,
      offsetY: event.clientY - rect.top - node.y,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
    };
  }

  onMouseMove(event: MouseEvent): void {
    const drag = this.dragState;
    if (!drag) {
      return;
    }
    if (Math.abs(event.clientX - drag.startX) + Math.abs(event.clientY - drag.startY) > 4) {
      drag.moved = true;
    }
    const rect = this.canvasRef().nativeElement.getBoundingClientRect();
    const x = clamp(event.clientX - rect.left - drag.offsetX, EDGE_PAD, CANVAS_W - NODE_W - EDGE_PAD);
    const y = clamp(event.clientY - rect.top - drag.offsetY, EDGE_PAD, CANVAS_H - NODE_H - EDGE_PAD);
    this.nodes.update((nodes) =>
      nodes.map((node) => (node.id === drag.nodeId ? { ...node, x, y } : node)));
  }

  onMouseUp(): void {
    const drag = this.dragState;
    this.dragState = null;
    if (drag && !drag.moved) {
      this.selectedNodeId.update((current) => (current === drag.nodeId ? null : drag.nodeId));
      this.selectedRelId.set(null);
      this.panelError.set('');
    }
  }

  isDragging(nodeId: number): boolean {
    return this.dragState?.nodeId === nodeId && this.dragState.moved;
  }

  selectEdge(relId: number): void {
    this.selectedNodeId.set(null);
    this.selectedRelId.update((current) => (current === relId ? null : relId));
    this.panelError.set('');
  }

  confirmSelected(): void {
    const rel = this.selectedRelationship();
    if (!rel) {
      return;
    }
    this.panelError.set('');
    this.relationshipService.confirmRelationship(this.projectId(), rel.id).subscribe({
      error: () => this.panelError.set('Could not confirm this relationship. Please try again.'),
    });
  }

  rejectSelected(): void {
    const rel = this.selectedRelationship();
    if (!rel) {
      return;
    }
    this.panelError.set('');
    this.relationshipService.rejectRelationship(this.projectId(), rel.id).subscribe({
      next: () => this.selectedRelId.set(null),
      error: () => this.panelError.set('Could not reject this relationship. Please try again.'),
    });
  }

  deleteSelected(): void {
    const rel = this.selectedRelationship();
    if (!rel) {
      return;
    }
    this.panelError.set('');
    this.relationshipService.deleteRelationship(this.projectId(), rel.id).subscribe({
      next: () => this.selectedRelId.set(null),
      error: () => this.panelError.set('Could not delete this relationship. Please try again.'),
    });
  }

  scan(): void {
    if (this.scanning()) {
      return;
    }
    this.scanning.set(true);
    this.scanMessage.set('');
    this.relationshipService.scanRelationships(this.projectId()).subscribe({
      next: (result) => {
        this.relationshipService.getRelationships(this.projectId()).subscribe({
          next: () => {
            this.scanning.set(false);
            this.scanMessage.set(result.createdRelationships > 0
              ? `Scan complete · ${result.createdRelationships} new suggestion${result.createdRelationships === 1 ? '' : 's'} found`
              : 'Scan complete · no new relationships found');
          },
          error: () => {
            this.scanning.set(false);
            this.scanMessage.set('Scan finished, but the results could not be loaded.');
          },
        });
      },
      error: () => {
        this.scanning.set(false);
        this.scanMessage.set('Scan failed. Please try again.');
      },
    });
  }

  openAddModal(): void {
    this.addError.set('');
    this.formDatasetAId = '';
    this.formDatasetBId = '';
    this.formColumnA = '';
    this.formColumnB = '';
    this.addModalOpen.set(true);
  }

  closeAddModal(): void {
    this.addModalOpen.set(false);
  }

  createManualRelationship(): void {
    const datasetAId = Number(this.formDatasetAId);
    const datasetBId = Number(this.formDatasetBId);
    const columnA = this.formColumnA.trim();
    const columnB = this.formColumnB.trim();
    if (!datasetAId || !datasetBId) {
      this.addError.set('Please choose both datasets.');
      return;
    }
    if (datasetAId === datasetBId) {
      this.addError.set('Please choose two different datasets.');
      return;
    }
    if (!columnA || !columnB) {
      this.addError.set('Please enter both column names.');
      return;
    }
    this.adding.set(true);
    this.addError.set('');
    this.relationshipService
      .createRelationship(this.projectId(), {
        datasetAId,
        datasetBId,
        sharedColumnA: columnA,
        sharedColumnB: columnB,
      })
      .subscribe({
        next: () => {
          this.adding.set(false);
          this.closeAddModal();
        },
        error: (error) => {
          this.adding.set(false);
          this.addError.set(readErrorMessage(error));
        },
      });
  }
}

function layoutNodes(datasets: OverviewDataset[]): DiagramNode[] {
  const count = datasets.length;
  const nodes: DiagramNode[] = [];
  if (count <= 8) {
    const cx = CANVAS_W / 2;
    const cy = CANVAS_H / 2;
    const radiusY = Math.min(CANVAS_H / 2 - NODE_H / 2 - EDGE_PAD - 10, 190);
    const radiusX = Math.min(CANVAS_W / 2 - NODE_W / 2 - EDGE_PAD - 10, 330);
    for (let i = 0; i < count; i++) {
      const angle = (2 * Math.PI * i) / count - Math.PI / 2;
      nodes.push(toNode(datasets[i], cx + radiusX * Math.cos(angle), cy + radiusY * Math.sin(angle)));
    }
    return nodes;
  }
  const cols = Math.ceil(Math.sqrt(count));
  const rows = Math.ceil(count / cols);
  const cellW = (CANVAS_W - 2 * EDGE_PAD - NODE_W) / Math.max(cols - 1, 1);
  const cellH = (CANVAS_H - 2 * EDGE_PAD - NODE_H) / Math.max(rows - 1, 1);
  for (let i = 0; i < count; i++) {
    const row = Math.floor(i / cols);
    const col = i % cols;
    const rowItems = Math.min(cols, count - row * cols);
    const rowOffset = ((cols - rowItems) * cellW) / 2;
    nodes.push(toNode(
      datasets[i],
      EDGE_PAD + col * cellW + rowOffset,
      EDGE_PAD + row * cellH,
    ));
  }
  return nodes;
}

function toNode(dataset: OverviewDataset, x: number, y: number): DiagramNode {
  return {
    id: dataset.id,
    name: dataset.name,
    rowCount: dataset.rowCount,
    qualityGrade: dataset.qualityGrade,
    x: clamp(x, EDGE_PAD, CANVAS_W - NODE_W - EDGE_PAD),
    y: clamp(y, EDGE_PAD, CANVAS_H - NODE_H - EDGE_PAD),
  };
}

function anchor(a: DiagramNode, b: DiagramNode): { x1: number; y1: number; x2: number; y2: number } {
  const acx = a.x + NODE_W / 2;
  const acy = a.y + NODE_H / 2;
  const bcx = b.x + NODE_W / 2;
  const bcy = b.y + NODE_H / 2;
  const dx = bcx - acx;
  const dy = bcy - acy;
  const sx = dx === 0 ? Infinity : NODE_W / 2 / Math.abs(dx);
  const sy = dy === 0 ? Infinity : NODE_H / 2 / Math.abs(dy);
  const s = Math.min(sx, sy);
  return { x1: acx + dx * s, y1: acy + dy * s, x2: bcx - dx * s, y2: bcy - dy * s };
}

function midpoint(x1: number, y1: number, x2: number, y2: number): { mx: number; my: number } {
  return { mx: (x1 + x2) / 2, my: (y1 + y2) / 2 };
}

function statusClass(status: DatasetRelationship['status']): string {
  switch (status) {
    case 'CONFIRMED':
      return 'confirmed';
    case 'MANUAL':
      return 'manual';
    default:
      return 'suggested';
  }
}

/**
 * Sibling links get their own dotted-teal look regardless of status;
 * foreign-key links keep the dashed/solid/purple status styles.
 */
function edgeClass(rel: DatasetRelationship): string {
  if (rel.relationshipType === 'SIBLING') {
    return 'sibling';
  }
  return statusClass(rel.status);
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

function readErrorMessage(error: unknown): string {
  const anyError = error as { error?: { message?: string } };
  const message = anyError?.error?.message ?? '';
  if (message.toLowerCase().includes('overlap')) {
    return message;
  }
  return message || 'Could not create the relationship. Please check the values and try again.';
}

