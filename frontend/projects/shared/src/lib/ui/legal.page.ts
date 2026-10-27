import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LogoComponent } from './logo.component';

/** Resumo dos documentos legais. Texto integral em docs/legal (modelos sujeitos a revisão jurídica). */
@Component({
  selector: 'ld-legal-page',
  imports: [RouterLink, LogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container stack page">
      <a routerLink="/"><ld-logo [size]="28" /></a>
      <article class="card stack">
        <h1>{{ doc() === 'terms' ? 'Termos de uso' : 'Política de privacidade' }}</h1>
        <p class="alert warn">Modelo inicial sujeito a revisão jurídica antes da operação comercial.</p>
        @if (doc() === 'terms') {
          <p>O Lucena Drive é uma plataforma de intermediação entre passageiros e motoristas parceiros
            independentes. O preço exibido antes da confirmação é calculado pelo servidor conforme a tarifa vigente
            na cidade e é mantido para a corrida confirmada.</p>
          <p>Cancelamentos após a chegada do motorista ou após o período de tolerância podem gerar taxa, informada
            antes da confirmação do cancelamento. Condutas inadequadas podem levar à suspensão da conta.</p>
        } @else {
          <p>Tratamos dados cadastrais (nome, CPF, data de nascimento, telefone, e-mail), dados de localização durante
            o uso, mensagens do chat da corrida, avaliações e dados financeiros, para executar o serviço, cumprir
            obrigações legais e garantir a segurança da plataforma (LGPD, art. 7º, II, V e IX).</p>
          <p>Mensagens do chat ficam visíveis aos participantes por 7 dias após a corrida e são retidas por até 180 dias
            para suporte e apuração de ocorrências; depois, são excluídas. O CPF é armazenado criptografado.</p>
          <p>Você pode solicitar acesso, correção, portabilidade e exclusão em Perfil › Privacidade.</p>
        }
      </article>
    </div>
  `,
  styles: `.page { padding: 24px 16px; max-width: 800px; }`,
})
export class LegalPage {
  readonly doc = input<'terms' | 'privacy'>('terms');
  protected readonly title = computed(() => this.doc());
}
