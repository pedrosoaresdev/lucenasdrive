import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

export interface BarPoint { label: string; value: number; display: string; }

/**
 * Gráfico de barras de série única (sem eixo duplo): barras finas com topo arredondado, grade
 * discreta, tooltip ao passar o mouse/focar e alternância para tabela (acessibilidade).
 */
@Component({
  selector: 'admin-bar-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="chart">
      <figcaption class="row between">
        <strong>{{ title() }}</strong>
        <button type="button" class="btn link small" (click)="asTable.set(!asTable())">{{ asTable() ? 'Ver gráfico' : 'Ver tabela' }}</button>
      </figcaption>
      @if (asTable()) {
        <div class="table-wrap"><table class="table"><thead><tr><th>Dia</th><th>{{ title() }}</th></tr></thead>
          <tbody>@for (p of points(); track p.label) { <tr><td>{{ p.label }}</td><td class="mono">{{ p.display }}</td></tr> }</tbody></table></div>
      } @else {
        <div class="plot" role="img" [attr.aria-label]="title() + ': ' + summary()">
          <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" preserveAspectRatio="none" aria-hidden="true">
            @for (g of grid(); track g) {
              <line [attr.x1]="0" [attr.x2]="W" [attr.y1]="g" [attr.y2]="g" class="grid" />
            }
            @for (b of bars(); track b.p.label; let i = $index) {
              <rect [attr.x]="b.x - 8" [attr.y]="0" [attr.width]="b.w + 16" [attr.height]="H" fill="transparent"
                    (mouseenter)="hover.set(i)" (mouseleave)="hover.set(-1)" />
              <path [attr.d]="b.d" class="bar" [class.on]="hover() === i" />
            }
          </svg>
          @if (hover() >= 0) {
            <div class="tip" [style.left.%]="(bars()[hover()].x + bars()[hover()].w / 2) / W * 100">
              <span class="small muted">{{ bars()[hover()].p.label }}</span><strong>{{ bars()[hover()].p.display }}</strong>
            </div>
          }
          <div class="axis small muted"><span>{{ points()[0]?.label }}</span><span>máx. {{ maxDisplay() }}</span><span>{{ points().at(-1)?.label }}</span></div>
        </div>
      }
    </figure>
  `,
  styles: `
    .chart { margin: 0; display: flex; flex-direction: column; gap: 8px; }
    .plot { position: relative; }
    svg { width: 100%; height: 160px; display: block; }
    .grid { stroke: var(--ld-border); stroke-width: 1; vector-effect: non-scaling-stroke; }
    .bar { fill: var(--ld-primary); opacity: .85; } .bar.on { opacity: 1; }
    .tip { position: absolute; top: 0; transform: translateX(-50%); background: var(--ld-surface); border: 1px solid var(--ld-border);
      box-shadow: var(--ld-shadow); border-radius: 8px; padding: 4px 8px; display: flex; flex-direction: column; pointer-events: none; white-space: nowrap; }
    .axis { display: flex; justify-content: space-between; margin-top: 4px; }
  `,
})
export class BarChartComponent {
  readonly title = input('');
  readonly points = input<BarPoint[]>([]);
  protected readonly W = 600;
  protected readonly H = 160;
  protected readonly hover = signal(-1);
  protected readonly asTable = signal(false);
  private readonly max = computed(() => Math.max(1, ...this.points().map((p) => p.value)));
  protected readonly maxDisplay = computed(() => this.points().find((p) => p.value === this.max())?.display ?? '0');
  protected readonly grid = computed(() => [0.25, 0.5, 0.75].map((f) => this.H - f * (this.H - 8)));
  protected readonly summary = computed(() => this.points().map((p) => `${p.label} ${p.display}`).join('; '));
  protected readonly bars = computed(() => {
    const pts = this.points();
    const slot = this.W / Math.max(1, pts.length);
    const w = Math.max(4, Math.min(28, slot * 0.55));
    return pts.map((p, i) => {
      const h = (p.value / this.max()) * (this.H - 8);
      const x = i * slot + (slot - w) / 2;
      const y = this.H - h;
      const r = Math.min(4, h, w / 2);
      const d = h <= 0 ? '' : `M${x},${this.H} V${y + r} Q${x},${y} ${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${this.H} Z`;
      return { p, x, w, d };
    });
  });
}
