import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { ChatMessage, ChatResponse, Conversation } from './chat';

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);

  send(message: string, conversationId: string | null, projectId: string | null): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${environment.apiUrl}/chat`, {
      message,
      conversationId: conversationId ?? undefined,
      projectId: projectId ?? undefined,
    });
  }

  listConversations(projectId: string | null): Observable<Conversation[]> {
    let params = new HttpParams();
    if (projectId) {
      params = params.set('projectId', projectId);
    }
    return this.http.get<Conversation[]>(`${environment.apiUrl}/chat/conversations`, { params });
  }

  history(conversationId: string): Observable<ChatMessage[]> {
    return this.http.get<ChatMessage[]>(`${environment.apiUrl}/chat/conversations/${conversationId}`);
  }
}
