import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { ChatMessage } from '../models/chat.model';
import { Page } from '../models/page.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);

  /** Messages of the currently open project conversation. */
  readonly messages = signal<ChatMessage[]>([]);
  readonly sending = signal(false);
  readonly loadingHistory = signal(false);
  readonly error = signal('');

  loadHistory(projectId: number): void {
    this.loadingHistory.set(true);
    this.error.set('');
    // The backend serves page 0 newest-first; flip it so the chat renders
    // oldest at the top.
    this.http.get<Page<ChatMessage>>(
      `${environment.apiUrl}/projects/${projectId}/chat/history`,
    ).subscribe({
      next: (page) => {
        this.messages.set(page.content.slice().reverse());
        this.loadingHistory.set(false);
      },
      error: () => {
        this.error.set('Could not load the chat history.');
        this.loadingHistory.set(false);
      },
    });
  }

  sendMessage(projectId: number, message: string): void {
    const trimmed = message.trim();
    if (!trimmed || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.error.set('');
    // Optimistic user bubble with a temporary negative id.
    const optimisticId = -Date.now();
    this.messages.update((current) => [
      ...current,
      { id: optimisticId, role: 'user', content: trimmed },
    ]);
    this.http.post<ChatMessage>(
      `${environment.apiUrl}/projects/${projectId}/chat`,
      { message: trimmed },
    ).subscribe({
      next: (reply) => {
        // The optimistic user bubble stays; just append the assistant reply.
        this.messages.update((current) => [...current, reply]);
        this.sending.set(false);
      },
      error: (err) => {
        // Drop the optimistic bubble; nothing was persisted backend-side.
        this.messages.update((current) =>
          current.filter((entry) => entry.id !== optimisticId),
        );
        this.error.set(extractError(err));
        this.sending.set(false);
      },
    });
  }

  clearConversation(projectId: number): void {
    this.http.delete(
      `${environment.apiUrl}/projects/${projectId}/chat/history`,
      { responseType: 'text' },
    ).subscribe({
      next: () => this.messages.set([]),
      error: () => this.error.set('Could not clear the conversation. Please try again.'),
    });
  }
}

function extractError(err: { status?: number; error?: { message?: string } }): string {
  if (err.status === 503) {
    return err.error?.message
      ?? 'The AI assistant is unavailable. Please make sure Ollama is running and the model is pulled.';
  }
  return err.error?.message ?? 'Could not send the message. Please try again.';
}
