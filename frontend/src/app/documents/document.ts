export const DOCUMENT_STATUSES = ['UPLOADED', 'PROCESSING', 'READY', 'FAILED'] as const;

export type DocumentStatus = (typeof DOCUMENT_STATUSES)[number];

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
  createdAt: string;
}

/** Extensions accepted by the backend; kept here so the file input and any client-side check agree with it. */
export const ALLOWED_DOCUMENT_EXTENSIONS = ['.pdf', '.docx', '.txt', '.md'];
