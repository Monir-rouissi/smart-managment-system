import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { AppDocument } from './document';

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/documents`;

  listForProject(projectId: string): Observable<AppDocument[]> {
    return this.http.get<AppDocument[]>(`${environment.apiUrl}/projects/${projectId}/documents`);
  }

  get(id: string): Observable<AppDocument> {
    return this.http.get<AppDocument>(`${this.baseUrl}/${id}`);
  }

  /** Content-Type is left for the browser to set (with the multipart boundary) — never set it manually here. */
  upload(file: File, projectId?: string): Observable<AppDocument> {
    const form = new FormData();
    form.append('file', file, file.name);
    const url = projectId ? `${this.baseUrl}?projectId=${encodeURIComponent(projectId)}` : this.baseUrl;
    return this.http.post<AppDocument>(url, form);
  }

  /**
   * Re-runs extraction + embedding for a document (ADMIN/MANAGER only).
   * Returns 202 with the document back in a pending state; the backend answers 409 while it is
   * already PROCESSING, which the caller should treat as "someone beat me to it", not as an error.
   */
  reprocess(id: string): Observable<AppDocument> {
    return this.http.post<AppDocument>(`${this.baseUrl}/${id}/reprocess`, {});
  }

  /** Fetches the file as a blob and triggers a browser download via a temporary anchor. */
  download(doc: AppDocument): void {
    this.http.get(`${this.baseUrl}/${doc.id}/download`, { responseType: 'blob' }).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = doc.name;
      a.click();
      URL.revokeObjectURL(url);
    });
  }
}
