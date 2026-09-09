import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMPTY, catchError, debounceTime, distinctUntilChanged, map, switchMap, tap } from 'rxjs';

import { Page } from '../core/page';
import { SEARCH_MODES, SearchHit, SearchMode, SnippetPart, splitSnippet } from './search';
import { SearchService } from './search.service';

const MIN_QUERY_LENGTH = 2;
const PAGE_SIZE = 20;

@Component({
  selector: 'app-search-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './search-page.html',
})
export class SearchPage {
  private readonly service = inject(SearchService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly modes = SEARCH_MODES;
  protected readonly control = new FormControl('', { nonNullable: true });
  protected readonly mode = signal<SearchMode>('HYBRID');
  protected readonly results = signal<Page<SearchHit> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Distinguishes "nothing typed yet" from "no results" — they mean different things. */
  protected readonly query = signal('');

  constructor() {
    this.control.valueChanges
      .pipe(
        map((v) => v.trim()),
        debounceTime(300),
        distinctUntilChanged(),
        tap((q) => this.query.set(q)),
        // switchMap, not mergeMap: a slow response for "cust" must never overwrite
        // the results for "customer". It also keeps the embeddings bill down.
        switchMap((q) => this.run(q)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  /** Mode is a click, not typing, so it re-runs immediately with no debounce. */
  setMode(mode: SearchMode): void {
    if (this.mode() === mode) return;
    this.mode.set(mode);
    this.run(this.query()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }

  private run(q: string) {
    if (q.length < MIN_QUERY_LENGTH) {
      this.results.set(null);
      this.error.set(null);
      this.loading.set(false);
      return EMPTY;
    }
    this.loading.set(true);
    this.error.set(null);
    return this.service.search({ q, mode: this.mode(), size: PAGE_SIZE }).pipe(
      tap((page) => {
        this.results.set(page);
        this.loading.set(false);
      }),
      catchError((err) => {
        this.loading.set(false);
        this.results.set(null);
        this.error.set(err?.error?.detail ?? 'Search failed.');
        return EMPTY;
      }),
    );
  }

  /** Snippets are plain text plus markers; nothing is ever rendered as HTML. */
  parts(hit: SearchHit): SnippetPart[] {
    return splitSnippet(hit.snippet);
  }

  modeLabel(mode: SearchMode): string {
    return mode.toLowerCase();
  }

  matchLabel(hit: SearchHit): string {
    return hit.match === 'BOTH' ? 'keyword + semantic' : hit.match.toLowerCase();
  }

  tooShort(): boolean {
    return this.query().length > 0 && this.query().length < MIN_QUERY_LENGTH;
  }
}
