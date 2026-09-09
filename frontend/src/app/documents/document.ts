export const DOCUMENT_STATUSES = ['UPLOADED', 'PROCESSING', 'READY', 'FAILED'] as const;

export type DocumentStatus = (typeof DOCUMENT_STATUSES)[number];

/** Statuses the ingestion worker will still move on its own — the UI polls while any document is in one of these. */
export const PENDING_DOCUMENT_STATUSES: readonly DocumentStatus[] = ['UPLOADED', 'PROCESSING'];

export interface AppDocument {
  id: string;
  name: string;
  mimeType: string;
  sizeBytes: number;
  status: DocumentStatus;
  projectId: string | null;
  projectName: string | null;
  uploadedById: string | null;
  uploadedByName: string | null;
  /** Number of chunks written by ingestion; 0 until the document is READY. */
  chunkCount: number;
  embeddingModel: string | null;
  /** Set only when status is FAILED. */
  errorMessage: string | null;
  processedAt: string | null;
  createdAt: string;
}

export function isDocumentPending(d: AppDocument): boolean {
  return PENDING_DOCUMENT_STATUSES.includes(d.status);
}

/** Extensions accepted by the backend; kept here so the file input and any client-side check agree with it. */
export const ALLOWED_DOCUMENT_EXTENSIONS = ['.pdf', '.docx', '.txt', '.md'];
