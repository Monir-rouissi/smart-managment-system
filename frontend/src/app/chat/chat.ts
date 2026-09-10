export type ChatRole = 'USER' | 'ASSISTANT';

export interface ChatSource {
  chunkId: string;
  documentId: string;
  documentName: string;
  pageOrSection: string | null;
  score: number;
}

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  sources: ChatSource[] | null;
  createdAt: string;
}

export interface ChatResponse {
  conversationId: string;
  message: ChatMessage;
}

export interface Conversation {
  id: string;
  projectId: string | null;
  title: string | null;
  updatedAt: string;
}
