import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService, LogoComponent } from '@lucena/shared';

/** Página de apresentação pública. */
@Component({
  selector: 'app-landing',
  imports: [RouterLink, LogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="container row between head">
      <ld-logo [size]="34" />
      <nav class="row">
        @if (auth.isLoggedIn()) {
          <a class="btn primary" routerLink="/inicio">Abrir o app</a>
        } @else {
          <a class="btn ghost" routerLink="/entrar">Entrar</a>
          <a class="btn primary" routerLink="/cadastro">Criar conta</a>
        }
      </nav>
    </header>
    <section class="hero">
      <div class="container hero-grid">
        <div class="fade-in">
          <span class="badge brand">Mobilidade urbana</span>
          <h1>Chegue bem. Vá de <span class="hl">Lucena Drive</span>.</h1>
          <p class="lead muted">Peça uma corrida em segundos, acompanhe o motorista no mapa, converse pelo chat
            da viagem e pague o preço combinado antes de embarcar.</p>
          <div class="row">
            <a class="btn primary lg" [routerLink]="auth.isLoggedIn() ? '/inicio' : '/cadastro'">Pedir uma corrida</a>
            <a class="btn ghost lg" href="/motorista" rel="noopener">Quero dirigir</a>
          </div>
        </div>
        <div class="phone card" aria-hidden="true">
          <div class="map-art">
            <svg viewBox="0 0 300 220"><path d="M20 190 C80 150, 90 80, 160 90 S 250 40, 280 30" fill="none"
              stroke="#4338ca" stroke-width="6" stroke-linecap="round"/><circle cx="20" cy="190" r="10" fill="#10b981"/>
              <circle cx="280" cy="30" r="10" fill="#4338ca"/></svg>
          </div>
          <div class="stack sheet-art">
            <strong>Lucena Essencial</strong>
            <span class="muted small">Preço definido antes de pedir</span>
            <span class="btn primary block">Confirmar</span>
          </div>
        </div>
      </div>
    </section>
    <section class="container features grid">
      @for (f of features; track f.t) {
        <article class="card flat"><h3>{{ f.t }}</h3><p class="muted">{{ f.d }}</p></article>
      }
    </section>
    <footer class="container row between foot small muted">
      <span>© {{ year }} Lucena Drive</span>
      <span class="row"><a routerLink="/termos">Termos de uso</a><a routerLink="/privacidade">Privacidade</a></span>
    </footer>
  `,
  styles: `
    .head { height: 72px; }
    .hero { padding: 32px 0 48px; background: radial-gradient(1200px 400px at 80% 0%, var(--ld-primary-soft), transparent); }
    .hero-grid { display: grid; gap: 32px; grid-template-columns: 1.2fr 1fr; align-items: center; }
    h1 { font-size: clamp(2rem, 5vw, 3.2rem); margin: 12px 0; }
    .hl { color: var(--ld-primary); }
    .lead { font-size: 1.1rem; max-width: 520px; }
    .phone { padding: 0; overflow: hidden; max-width: 340px; justify-self: center; width: 100%; }
    .map-art { background: var(--ld-surface-2); padding: 16px; }
    .sheet-art { padding: 16px; }
    .features { padding: 24px 16px 48px; }
    .foot { padding: 24px 16px; border-top: 1px solid var(--ld-border); }
    @media (max-width: 800px) { .hero-grid { grid-template-columns: 1fr; } }
  `,
})
export class LandingPage {
  protected readonly auth = inject(AuthService);
  protected readonly year = new Date().getFullYear();
  protected readonly features = [
    { t: 'Preço antes de pedir', d: 'Você vê a estimativa calculada pelo servidor e o valor não muda depois da confirmação.' },
    { t: 'Acompanhamento ao vivo', d: 'Posição do motorista, placa, modelo e tempo estimado de chegada em tempo real.' },
    { t: 'Chat da viagem', d: 'Combine o ponto de embarque sem expor seu telefone. A conversa pertence só à corrida.' },
    { t: 'Avaliação em estrelas', d: 'Sua opinião ajuda a manter a qualidade. Cada corrida recebe uma única avaliação.' },
  ];
}
