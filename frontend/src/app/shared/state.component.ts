import { Component, computed, input } from '@angular/core';
import { stateInfo } from './format';

/** A bill or case state, shown as words with a tone mark. Colour is never the only signal. */
@Component({
  selector: 'app-state',
  template: `<span class="state" [attr.data-tone]="info().tone"><span class="state-mark" aria-hidden="true"></span>{{ info().label }}</span>`,
})
export class StateComponent {
  readonly value = input<string | null | undefined>(null);
  protected readonly info = computed(() => stateInfo(this.value()));
}
