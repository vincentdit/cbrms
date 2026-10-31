import { Injectable, signal } from '@angular/core';
import { AppSettings } from './models';

/** Runtime settings from /config.json, so one build can be deployed to every environment. */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  readonly settings = signal<AppSettings>({ simulator: false, authMode: 'basic', environmentLabel: '' });

  async load(): Promise<void> {
    try {
      const response = await fetch('config.json', { cache: 'no-store' });
      if (response.ok) {
        this.settings.set({ ...this.settings(), ...(await response.json()) });
      }
    } catch {
      // Keep safe defaults: no simulator.
    }
  }
}
