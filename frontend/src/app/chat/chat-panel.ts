import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, Input, OnChanges, SimpleChanges, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';

import { ChatMessage, ChatSource, Conversation } from './chat';
import { ChatService } from './chat.service';

/**
 * Self-contained RAG chat panel. `projectId` unset (or null) is the "global"
 * panel -- retrieval covers everything the caller can see, same as
 * `GET /api/search` with no `projectId`. Set it to scope the panel (and every
 * conversation started from it) to one project, matching the backend rule that
 * a conversation's project is fixed at creation and ignored afterwards.
 */
@Component({
  selector: 'app-chat-panel',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './chat-panel.html',
})
export class ChatPanel implements OnChanges {
  @Input() projectId: string | null = null;

  private readonly service = inject(ChatService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly conversations = signal<Conversation[]>([]);
  protected readonly activeId = signal<string | null>(null);
  protected readonly messages = signal<ChatMessage[]>([]);
  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  protected readonly loadingHistory = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.startNewConversation();
      this.loadConversations();
    }
  }

  private loadConversations(): void {
    this.service
      .listConversations(this.projectId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => this.conversations.set(list),
        error: () => this.conversations.set([]),
      });
  }

  startNewConversation(): void {
    this.activeId.set(null);
    this.messages.set([]);
    this.error.set(null);
  }

  openConversation(c: Conversation): void {
    if (this.activeId() === c.id) return;
    this.activeId.set(c.id);
    this.error.set(null);
    this.loadingHistory.set(true);
    this.service
      .history(c.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (history) => {
          this.messages.set(history);
          this.loadingHistory.set(false);
        },
        error: (err) => {
          this.loadingHistory.set(false);
          this.error.set(err?.error?.detail ?? 'Could not load that conversation.');
        },
      });
  }

  send(): void {
    const text = this.draft().trim();
    if (!text || this.sending()) return;

    const optimisticUser: ChatMessage = {
      id: `pending-${Date.now()}`,
      role: 'USER',
      content: text,
      sources: null,
      createdAt: new Date().toISOString(),
    };
    this.messages.update((m) => [...m, optimisticUser]);
    this.draft.set('');
    this.sending.set(true);
    this.error.set(null);

    this.service
      .send(text, this.activeId(), this.projectId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.sending.set(false);
          const isNewConversation = this.activeId() === null;
          this.activeId.set(res.conversationId);
          this.messages.update((m) => [...m, res.message]);
          if (isNewConversation) {
            this.loadConversations();
          }
        },
        error: (err) => {
          this.sending.set(false);
          // Roll back the optimistic user turn: it was never persisted if the call failed.
          this.messages.update((m) => m.filter((msg) => msg.id !== optimisticUser.id));
          this.draft.set(text);
          this.error.set(err?.error?.detail ?? 'The assistant is unavailable right now.');
        },
      });
  }

  /** Grouped by cited document, in first-seen order, so the same document is not repeated per chunk. */
  groupedSources(sources: ChatSource[] | null): ChatSource[] {
    if (!sources) return [];
    const seen = new Set<string>();
    return sources.filter((s) => (seen.has(s.documentId) ? false : (seen.add(s.documentId), true)));
  }

  conversationLabel(c: Conversation): string {
    return c.title?.trim() || 'New conversation';
  }
}
