import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { Page } from '../core/page';
import { SearchHit, SearchMode } from './search';

@Injectable({ providedIn: 'root' })
export class SearchService {
  private readonly http = inject(HttpClient);

  search(options: {
    q: string;
    mode: SearchMode;
    projectId?: string | null;
    page?: number;
    size?: number;
  }): Observable<Page<SearchHit>> {
    let params = new HttpParams().set('q', options.q).set('mode', options.mode);
    if (options.projectId) {
      params = params.set('projectId', options.projectId);
    }
    if (options.page != null) {
      params = params.set('page', options.page);
    }
    if (options.size != null) {
      params = params.set('size', options.size);
    }
    // No `sort` parameter: results are ranked by relevance and the API rejects one.
    return this.http.get<Page<SearchHit>>(`${environment.apiUrl}/search`, { params });
  }
}
