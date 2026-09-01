import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { Page } from '../core/page';
import { Customer, CustomerRequest } from './customer';

@Injectable({ providedIn: 'root' })
export class CustomerService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/customers`;

  list(opts: { q?: string; page?: number; size?: number; sort?: string } = {}): Observable<Page<Customer>> {
    let params = new HttpParams();
    if (opts.q) params = params.set('q', opts.q);
    if (opts.page != null) params = params.set('page', opts.page);
    if (opts.size != null) params = params.set('size', opts.size);
    if (opts.sort) params = params.set('sort', opts.sort);
    return this.http.get<Page<Customer>>(this.baseUrl, { params });
  }

  get(id: string): Observable<Customer> {
    return this.http.get<Customer>(`${this.baseUrl}/${id}`);
  }

  create(body: CustomerRequest): Observable<Customer> {
    return this.http.post<Customer>(this.baseUrl, body);
  }

  update(id: string, body: CustomerRequest): Observable<Customer> {
    return this.http.put<Customer>(`${this.baseUrl}/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
