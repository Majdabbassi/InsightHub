import {
  AfterViewInit,
  ChangeDetectionStrategy,
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
import { Icon } from '../../../shared/icons/icon';
import { ChatMessage } from '../../../core/models/chat.model';
import { ChatService } from '../../../core/services/chat.service';

/**
 * Floating AI-assistant chat for one project. Opens as a side panel over the
 * page; history is loaded lazily on first open and kept while navigating
 * within the same project instance.
 */
@Component({
  selector: 'app-project-chat',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Icon],
  templateUrl: './project-chat.html',
  styleUrl: './project-chat.scss',
})
export class ProjectChat implements AfterViewInit {
  readonly projectId = input.required<number>();

  private readonly chatService = inject(ChatService);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly open = signal(false);
  readonly draft = signal('');
  readonly confirmingClear = signal(false);

  readonly messages = this.chatService.messages;
  readonly sending = this.chatService.sending;
  readonly loadingHistory = this.chatService.loadingHistory;
  readonly error = this.chatService.error;

  /** Assistant message ids whose executed SQL is currently expanded. */
  readonly expandedSqlIds = signal<Set<number>>(new Set<number>());

  /** True when the last message is an assistant reply (used to scroll). */
  readonly lastMessage = computed<ChatMessage | null>(() => {
    const list = this.messages();
    return list.length ? list[list.length - 1] : null;
  });

  private readonly scrollContainer =
    viewChild<ElementRef<HTMLElement>>('scrollContainer');

  constructor() {
    effect(() => {
      // Track messages + panel state; scroll after render.
      this.messages();
      this.open();
      setTimeout(() => this.scrollToBottom());
    });
  }

  ngAfterViewInit(): void {
    if (this.open()) {
      this.chatService.loadHistory(this.projectId());
    }
  }

  toggle(): void {
    const next = !this.open();
    this.open.set(next);
    if (next && this.messages().length === 0 && !this.loadingHistory()) {
      this.chatService.loadHistory(this.projectId());
    }
  }

  close(): void {
    this.open.set(false);
    this.confirmingClear.set(false);
  }

  send(): void {
    const text = this.draft().trim();
    if (!text || this.sending()) {
      return;
    }
    this.draft.set('');
    this.chatService.sendMessage(this.projectId(), text);
  }

  requestClear(): void {
    this.confirmingClear.set(true);
  }

  cancelClear(): void {
    this.confirmingClear.set(false);
  }

  confirmClear(): void {
    this.confirmingClear.set(false);
    this.chatService.clearConversation(this.projectId());
  }

  trackById(index: number, message: ChatMessage): number {
    return message.id;
  }

  toggleSql(message: ChatMessage): void {
    const next = new Set(this.expandedSqlIds());
    if (next.has(message.id)) {
      next.delete(message.id);
    } else {
      next.add(message.id);
    }
    this.expandedSqlIds.set(next);
  }

  private scrollToBottom(): void {
    const container = this.scrollContainer()?.nativeElement;
    if (container) {
      container.scrollTop = container.scrollHeight;
    }
  }
}
