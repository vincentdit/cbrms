import { Component, computed, input } from '@angular/core';

/** Originating TIRDO platform, shown as its code so colour is never the only signal. */
@Component({
  selector: 'app-platform',
  template: `<span class="platform" [attr.data-platform]="key()">{{ label() }}</span>`,
})
export class PlatformComponent {
  readonly code = input.required<string>();
  protected readonly key = computed(() => this.code().trim().toLowerCase());
  protected readonly label = computed(() => this.code().trim().toUpperCase());
}
