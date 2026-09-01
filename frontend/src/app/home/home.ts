import { Component, inject, signal } from '@angular/core';

import { HealthService } from '../core/health.service';

@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class Home {
  private readonly health = inject(HealthService);

  protected readonly status = signal<'checking' | 'up' | 'down'>('checking');
  protected readonly detail = signal<string>('');

  constructor() {
    this.health.check().subscribe({
      next: (res) => {
        this.status.set(res.status === 'UP' ? 'up' : 'down');
        this.detail.set(`${res.service} · ${res.timestamp}`);
      },
      error: () => {
        this.status.set('down');
        this.detail.set('Backend unreachable at /api/health');
      },
    });
  }
}
