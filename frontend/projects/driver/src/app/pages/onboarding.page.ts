import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ref, uploadBytes } from 'firebase/storage';
import { ApiError, ApiService, AuthService, FirebaseService, StateViewComponent, ToastService } from '@lucena/shared';

interface DriverDocument { type: string; status: string; vehicleId: string | null; reviewNote: string | null; expiresOn: string | null; }
interface Vehicle { id: string; make: string; model: string; year: number; color: string; plate: string; category: string; status: string; statusReason: string | null; }
interface Overview {
  profile: { status: string; statusReason: string | null; documents: DriverDocument[]; activeVehicleId: string | null };
  vehicles: Vehicle[]; online: boolean; currentRideId: string | null; missingRequirements: string[];
}

const DOC_LABEL: Record<string, string> = {
  CNH: 'CNH (frente e verso)', CRIMINAL_RECORD: 'Certidão de antecedentes criminais', PROFILE_PHOTO: 'Foto de perfil (rosto)',
  PROOF_OF_ADDRESS: 'Comprovante de residência (opcional)', CRLV: 'CRLV do veículo',
};
export const DRIVER_STATUS_LABEL: Record<string, string> = {
  INCOMPLETE: 'Cadastro incompleto', PENDING_REVIEW: 'Pendente de análise', APPROVED: 'Aprovado', REJECTED: 'Recusado',
  SUSPENDED: 'Suspenso', DOCUMENTS_EXPIRED: 'Documentação vencida',
};

/** Documentos (área privada do Storage), veículos e envio do cadastro para análise. */
@Component({
  selector: 'app-onboarding',
  imports: [FormsModule, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <h1>Documentos e veículos</h1>
      @if (!data()) { <ld-state state="loading" /> }
      @else {
        @let d = data()!;
        <div class="alert" [class]="statusTone()">
          <strong>Status: {{ statusLabel[d.profile.status] }}</strong>
          @if (d.profile.statusReason) { <span> · {{ d.profile.statusReason }}</span> }
          @if (d.profile.status === 'PENDING_REVIEW') { <p class="small">Nossa equipe está analisando seus documentos.</p> }
        </div>

        <section class="card stack">
          <h2>Veículo</h2>
          @for (v of d.vehicles; track v.id) {
            <div class="row between item">
              <span><strong>{{ v.make }} {{ v.model }}</strong> {{ v.year }} · {{ v.color }} · <span class="mono">{{ v.plate }}</span> · {{ v.category }}</span>
              <span class="row">
                <span class="badge" [class.ok]="v.status === 'APPROVED'" [class.warn]="v.status === 'PENDING_REVIEW'"
                      [class.err]="v.status === 'REJECTED' || v.status === 'SUSPENDED'">{{ v.status }}</span>
                @if (d.profile.activeVehicleId === v.id) { <span class="badge brand">Ativo</span> }
                @else { <button class="btn sm ghost" (click)="activate(v.id)">Usar este</button> }
              </span>
            </div>
            <div class="doc row between">
              <span class="small">{{ docLabel['CRLV'] }} · <span class="muted">{{ docStatus('CRLV', v.id) }}</span></span>
              <label class="btn sm ghost">Enviar<input type="file" hidden accept="application/pdf,image/jpeg,image/png,image/webp" (change)="upload($event, 'CRLV', v.id)" /></label>
            </div>
          }
          <details [open]="!d.vehicles.length">
            <summary>Adicionar veículo</summary>
            <div class="grid form">
              <input class="input" placeholder="Marca" [(ngModel)]="vehicle.make" aria-label="Marca" />
              <input class="input" placeholder="Modelo" [(ngModel)]="vehicle.model" aria-label="Modelo" />
              <input class="input" type="number" placeholder="Ano" [(ngModel)]="vehicle.year" aria-label="Ano" />
              <input class="input" placeholder="Cor" [(ngModel)]="vehicle.color" aria-label="Cor" />
              <input class="input" placeholder="Placa (ABC1D23)" [(ngModel)]="vehicle.plate" aria-label="Placa" />
              <select class="input" [(ngModel)]="vehicle.category" aria-label="Categoria">
                <option value="ESSENCIAL">Lucena Essencial</option><option value="CONFORTO">Lucena Conforto</option>
                <option value="EXECUTIVO">Lucena Executivo</option></select>
            </div>
            <button class="btn primary" (click)="addVehicle()" [disabled]="busy()">Salvar veículo</button>
          </details>
        </section>

        <section class="card stack">
          <h2>Documentos pessoais</h2>
          <p class="small muted">Arquivos PDF, JPG, PNG ou WebP até 10 MB. Ficam em área privada, visíveis apenas à equipe de análise.</p>
          @for (t of personalDocs; track t) {
            <div class="doc row between">
              <span>{{ docLabel[t] }} · <span class="small muted">{{ docStatus(t, null) }}</span></span>
              <label class="btn sm ghost" [class.disabled]="uploading() === t">{{ uploading() === t ? 'Enviando…' : 'Enviar' }}
                <input type="file" hidden accept="application/pdf,image/jpeg,image/png,image/webp" (change)="upload($event, t, null)" /></label>
            </div>
          }
        </section>

        @if (d.missingRequirements.length && canSubmit()) {
          <p class="alert warn small">Pendências: {{ missingText() }}</p>
        }
        @if (canSubmit()) {
          <button class="btn primary lg" (click)="submit()" [disabled]="busy() || d.missingRequirements.length > 0">Enviar para análise</button>
        }
      }
    </div>
  `,
  styles: `
    .page { max-width: 820px; padding-top: 24px; padding-bottom: 24px; }
    .doc { padding: 10px 0; border-top: 1px solid var(--ld-border); }
    .form { grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); margin: 12px 0; }
    summary { cursor: pointer; font-weight: 600; }
  `,
})
export class OnboardingPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FirebaseService);
  private readonly toast = inject(ToastService);
  protected readonly data = signal<Overview | null>(null);
  protected readonly busy = signal(false);
  protected readonly uploading = signal<string | null>(null);
  protected readonly docLabel = DOC_LABEL;
  protected readonly statusLabel = DRIVER_STATUS_LABEL;
  protected readonly personalDocs = ['CNH', 'CRIMINAL_RECORD', 'PROFILE_PHOTO', 'PROOF_OF_ADDRESS'];
  protected vehicle = { make: '', model: '', year: new Date().getFullYear() - 3, color: '', plate: '', category: 'ESSENCIAL' };

  protected readonly canSubmit = computed(() => ['INCOMPLETE', 'REJECTED'].includes(this.data()?.profile.status ?? ''));
  protected readonly statusTone = computed(() => {
    const s = this.data()?.profile.status;
    return s === 'APPROVED' ? 'ok' : s === 'REJECTED' || s === 'SUSPENDED' ? 'err' : s === 'PENDING_REVIEW' ? 'info' : 'warn';
  });
  protected readonly missingText = computed(() => (this.data()?.missingRequirements ?? [])
    .map((m) => (m === 'VEHICLE' ? 'cadastrar veículo' : DOC_LABEL[m.replace('DOCUMENT_', '')] ?? m)).join(', '));

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.data.set(await this.api.get<Overview>('/drivers/me'));
  }

  protected docStatus(type: string, vehicleId: string | null): string {
    const doc = this.data()?.profile.documents?.find((d) => d.type === type && (d.vehicleId ?? null) === vehicleId);
    if (!doc) return 'não enviado';
    const map: Record<string, string> = { PENDING: 'em análise', APPROVED: 'aprovado', REJECTED: 'recusado', EXPIRED: 'vencido' };
    return map[doc.status] + (doc.reviewNote ? ` (${doc.reviewNote})` : '');
  }

  protected async upload(ev: Event, type: string, vehicleId: string | null): Promise<void> {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) {
      this.toast.error('Arquivo maior que 10 MB.');
      return;
    }
    this.uploading.set(type);
    try {
      const ext = file.name.split('.').pop()?.toLowerCase() ?? 'bin';
      const path = `driverDocuments/${this.auth.me()!.uid}/${type}${vehicleId ? '-' + vehicleId : ''}-${Date.now()}.${ext}`;
      await uploadBytes(ref(this.fb.storage, path), file, { contentType: file.type });
      await this.api.post('/drivers/me/documents', { type, storagePath: path, vehicleId });
      this.toast.ok('Documento enviado.');
      await this.reload();
    } catch (e) {
      this.toast.error(ApiError.from(e).message || 'Falha no envio.');
    } finally {
      this.uploading.set(null);
    }
  }

  protected async addVehicle(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.post('/drivers/me/vehicles', { ...this.vehicle, year: Number(this.vehicle.year) });
      this.toast.ok('Veículo cadastrado.');
      await this.reload();
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async activate(id: string): Promise<void> {
    await this.api.post(`/drivers/me/vehicles/${id}/activate`).catch((e) => this.toast.error(ApiError.from(e).message));
    await this.reload();
  }

  protected async submit(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.post('/drivers/me/submit');
      this.toast.ok('Cadastro enviado para análise.');
      await this.reload();
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
