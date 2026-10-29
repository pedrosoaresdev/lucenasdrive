import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, computed, effect, inject, input, output, signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Unsubscribe, collection, doc, limit, onSnapshot, orderBy, query } from 'firebase/firestore';
import { ApiService } from '../core/api.service';
import { ApiError } from '../core/errors';
import { FirebaseService } from '../core/firebase.service';
import { fmtTime, toDate } from '../core/format';
import { ChatMessage, Page, RideChatDoc } from '../core/models';
import { AvatarComponent } from './avatar.component';

const MAX_LEN = 500;

/**
 * Chat da corrida.
 * - Recebimento: listener do Firestore nas últimas mensagens (regras: só participantes).
 * - Envio: API (autorização no servidor, carimbo de tempo do servidor, limite e deduplicação por
 *   clientMessageId — reenvios após falha de rede não duplicam).
 * - Histórico antigo: paginação pela API.
 */
@Component({
  selector: 'ld-chat-panel',
  imports: [FormsModule, AvatarComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="chat" aria-label="Chat da corrida">
      <header class="head">
        <ld-avatar [path]="counterpartPhoto()" [name]="counterpartName()" [size]="40" />
        <div class="who">
          <strong>{{ counterpartName() }}</strong>
          <span class="small muted">{{ statusText() }}</span>
        </div>
        <span class="conn" [class.off]="!connected()" [attr.title]="connected() ? 'Conectado' : 'Reconectando…'">
          <span class="sr-only">{{ connected() ? 'Conectado' : 'Reconectando' }}</span>
        </span>
        @if (closable()) {
          <button type="button" class="btn ghost sm" (click)="closed.emit()" aria-label="Fechar chat">Fechar</button>
        }
      </header>

      <div #scroller class="msgs" (scroll)="onScroll()" role="log" aria-live="polite" aria-relevant="additions">
        @if (hasOlder()) {
          <button class="btn sm ghost older" type="button" (click)="loadOlder()" [disabled]="loadingOlder()">
            {{ loadingOlder() ? 'Carregando…' : 'Mensagens anteriores' }}
          </button>
        }
        @if (state() === 'loading') { <p class="muted small center">Carregando conversa…</p> }
        @if (state() === 'error') { <p class="small center err">Não foi possível carregar o chat.</p> }
        @if (state() === 'ready' && !messages().length) {
          <p class="muted small center">Nenhuma mensagem ainda. Combine detalhes do embarque por aqui.</p>
        }
        @for (m of messages(); track m.id) {
          @if (m.type === 'SYSTEM') {
            <div class="sys"><span>{{ m.text }}</span> <time>{{ time(m) }}</time></div>
          } @else {
            <div class="bubble" [class.mine]="m.senderId === myUid()" [class.failed]="m.failed">
              @if (m.type === 'LOCATION' && m.lat != null && m.lng != null) {
                <p>📍 {{ m.text }}</p>
                <a class="loc" [href]="mapLink(m)" target="_blank" rel="noopener">Abrir no mapa ↗</a>
              } @else {
                <p>{{ m.text }}</p>
              }
              <span class="meta">
                <time>{{ time(m) }}</time>
                @if (m.senderId === myUid()) {
                  @if (m.failed) {
                    <button type="button" class="retry" (click)="retry(m)">Falhou · reenviar</button>
                  } @else if (m.pending) { <span aria-label="enviando">◷</span> }
                  @else if (isRead(m)) { <span class="read" aria-label="lida">✓✓</span> }
                  @else { <span aria-label="entregue">✓</span> }
                }
              </span>
            </div>
          }
        }
      </div>

      @if (newBelow()) {
        <button type="button" class="btn sm primary jump" (click)="scrollToBottom(true)">Novas mensagens ↓</button>
      }

      @if (writable()) {
        <form class="composer" (submit)="send($event)">
          <label class="sr-only" for="chat-input">Mensagem</label>
          <textarea id="chat-input" class="input" rows="1" [(ngModel)]="draft" name="draft" [maxlength]="maxLen"
                    placeholder="Escreva uma mensagem" (keydown)="onKey($event)" enterkeyhint="send"></textarea>
          <button class="btn ghost icon-btn" type="button" (click)="sendLocation()" [disabled]="locating()"
                  aria-label="Enviar minha localização" title="Enviar minha localização">📍</button>
          <button class="btn primary icon-btn" type="submit" [disabled]="!draft.trim()" aria-label="Enviar">➤</button>
        </form>
        @if (draft.length > maxLen - 60) { <p class="small muted count">{{ draft.length }}/{{ maxLen }}</p> }
      } @else {
        <p class="small muted center closed">Chat em modo somente leitura.</p>
      }
      @if (sendError()) { <p class="small err center">{{ sendError() }}</p> }
    </section>
  `,
  styles: `
    .chat { display: flex; flex-direction: column; height: 100%; min-height: 360px; position: relative; }
    .head { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border-bottom: 1px solid var(--ld-border); }
    .who { display: flex; flex-direction: column; flex: 1; min-width: 0; }
    .conn { width: 10px; height: 10px; border-radius: 50%; background: var(--ld-accent); }
    .conn.off { background: var(--ld-warning); animation: ld-pulse 1.4s infinite; }
    .msgs { flex: 1; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 8px; background: var(--ld-bg); }
    .bubble { max-width: 78%; align-self: flex-start; background: var(--ld-surface); border: 1px solid var(--ld-border);
      padding: 8px 12px; border-radius: 16px 16px 16px 4px; }
    .bubble.mine { align-self: flex-end; background: var(--ld-primary); color: var(--ld-on-primary); border-color: transparent;
      border-radius: 16px 16px 4px 16px; }
    .bubble.failed { opacity: .7; outline: 1px dashed var(--ld-danger); }
    .bubble p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
    .bubble .loc { display: inline-block; margin-top: 4px; font-weight: 600; font-size: .85rem; color: inherit; text-decoration: underline; }
    .meta { display: flex; gap: 6px; justify-content: flex-end; font-size: .72rem; opacity: .75; margin-top: 2px; }
    .read { color: var(--ld-accent); }
    .bubble.mine .read { color: #b8f5dc; }
    .retry { background: none; border: 0; color: inherit; text-decoration: underline; cursor: pointer; font-size: .72rem; }
    .sys { align-self: center; font-size: .78rem; color: var(--ld-text-muted); background: var(--ld-surface-2);
      padding: 4px 12px; border-radius: 999px; }
    .sys time { opacity: .7; margin-left: 4px; }
    .composer { display: flex; gap: 8px; padding: 10px; border-top: 1px solid var(--ld-border); background: var(--ld-surface); }
    .composer textarea { min-height: 44px; max-height: 120px; resize: none; }
    .center { text-align: center; } .err { color: var(--ld-danger); }
    .older { align-self: center; }
    .jump { position: absolute; bottom: 76px; left: 50%; transform: translateX(-50%); }
    .count { text-align: right; padding: 0 12px; margin: 0; } .closed { padding: 12px; margin: 0; }
  `,
})
export class ChatPanelComponent implements OnInit {
  private readonly fb = inject(FirebaseService);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly scroller = viewChild.required<ElementRef<HTMLDivElement>>('scroller');

  readonly rideId = input.required<string>();
  readonly myUid = input.required<string>();
  readonly counterpartName = input('');
  readonly counterpartPhoto = input<string | null | undefined>(null);
  readonly closable = input(false);
  readonly closed = output<void>();

  protected readonly maxLen = MAX_LEN;
  protected draft = '';
  protected readonly live = signal<ChatMessage[]>([]);
  protected readonly older = signal<ChatMessage[]>([]);
  protected readonly local = signal<ChatMessage[]>([]);
  protected readonly chatDoc = signal<RideChatDoc | null>(null);
  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly connected = signal(true);
  protected readonly hasOlder = signal(false);
  protected readonly loadingOlder = signal(false);
  protected readonly newBelow = signal(false);
  protected readonly sendError = signal('');
  private nearBottom = true;
  private olderCursor: string | null = null;
  private readTimer?: ReturnType<typeof setTimeout>;

  protected readonly messages = computed(() => {
    const map = new Map<string, ChatMessage>();
    for (const m of [...this.older(), ...this.live()]) map.set(m.id, m);
    // Mensagens locais pendentes até o servidor confirmar (mesmo id determinístico).
    for (const m of this.local()) if (!map.has(m.id)) map.set(m.id, m);
    return [...map.values()].sort((a, b) => (toDate(a.createdAt)?.getTime() ?? 0) - (toDate(b.createdAt)?.getTime() ?? 0));
  });

  protected readonly writable = computed(() => this.chatDoc()?.status === 'OPEN');
  protected readonly statusText = computed(() => {
    const s = this.chatDoc()?.status;
    return s === 'OPEN' ? 'Conversa da corrida' : s === 'READ_ONLY' ? 'Corrida encerrada' : 'Carregando…';
  });

  constructor() {
    effect(() => {
      this.messages();
      queueMicrotask(() => (this.nearBottom ? this.scrollToBottom(false) : this.newBelow.set(true)));
    });
  }

  ngOnInit(): void {
    const id = this.rideId();
    const unsubs: Unsubscribe[] = [];
    unsubs.push(onSnapshot(doc(this.fb.firestore, `rideChats/${id}`), { includeMetadataChanges: true }, (snap) => {
      this.connected.set(!snap.metadata.fromCache);
      this.chatDoc.set(snap.exists() ? ({ id: snap.id, ...snap.data() } as RideChatDoc) : null);
    }, () => this.state.set('error')));
    const q = query(collection(this.fb.firestore, `rideChats/${id}/messages`), orderBy('createdAt', 'desc'), limit(50));
    unsubs.push(onSnapshot(q, { includeMetadataChanges: true }, (snap) => {
      this.connected.set(!snap.metadata.fromCache);
      const msgs = snap.docs.map((d) => ({ id: d.id, ...d.data() }) as ChatMessage);
      this.live.set(msgs);
      if (this.state() === 'loading') {
        this.hasOlder.set(msgs.length === 50);
        const last = msgs[msgs.length - 1];
        this.olderCursor = last ? String(toDate(last.createdAt)?.getTime()) : null;
      }
      this.local.update((l) => l.filter((m) => !msgs.some((x) => x.id === m.id)));
      this.state.set('ready');
      this.scheduleRead();
    }, () => this.state.set('error')));
    const onOnline = () => this.connected.set(navigator.onLine);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOnline);
    this.destroyRef.onDestroy(() => {
      unsubs.forEach((u) => u());
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOnline);
      clearTimeout(this.readTimer);
    });
  }

  protected time(m: ChatMessage): string {
    return fmtTime(m.createdAt);
  }

  protected isRead(m: ChatMessage): boolean {
    const d = this.chatDoc();
    if (!d) return false;
    const other = d.participantIds.find((p) => p !== this.myUid());
    const readAt = other ? d.lastReadAt?.[other] : undefined;
    return !!readAt && (toDate(m.createdAt)?.getTime() ?? Infinity) <= readAt;
  }

  protected onKey(e: KeyboardEvent): void {
    // Enter envia no computador; em telas de toque, Enter quebra linha (envio pelo botão).
    const coarse = window.matchMedia?.('(pointer: coarse)').matches;
    if (e.key === 'Enter' && !e.shiftKey && !coarse && !e.isComposing) {
      e.preventDefault();
      this.send();
    }
  }

  protected send(e?: Event): void {
    e?.preventDefault();
    const text = this.draft.trim();
    if (!text) return;
    if (text.length > MAX_LEN) {
      this.sendError.set(`Máximo de ${MAX_LEN} caracteres.`);
      return;
    }
    const clientMessageId = crypto.randomUUID();
    const pending: ChatMessage = { id: `${this.myUid()}_${clientMessageId}`, rideId: this.rideId(), senderId: this.myUid(),
      senderRole: 'PASSENGER', type: 'TEXT', text, clientMessageId, createdAt: Date.now(), pending: true };
    this.local.update((l) => [...l, pending]);
    this.draft = '';
    this.nearBottom = true;
    void this.deliver(pending);
  }

  protected readonly locating = signal(false);

  /** Link universal (abre o app de mapas do aparelho ou o OpenStreetMap). */
  protected mapLink(m: ChatMessage): string {
    return `https://www.openstreetmap.org/?mlat=${m.lat}&mlon=${m.lng}#map=18/${m.lat}/${m.lng}`;
  }

  /** "Estou aqui": compartilha a posição atual na conversa da corrida (só com o outro participante). */
  protected sendLocation(): void {
    if (!('geolocation' in navigator)) {
      this.sendError.set('Seu dispositivo não oferece localização.');
      return;
    }
    this.locating.set(true);
    navigator.geolocation.getCurrentPosition((pos) => {
      this.locating.set(false);
      const clientMessageId = crypto.randomUUID();
      const pending: ChatMessage = { id: `${this.myUid()}_${clientMessageId}`, rideId: this.rideId(), senderId: this.myUid(),
        senderRole: 'PASSENGER', type: 'LOCATION', text: 'Minha localização', clientMessageId, createdAt: Date.now(),
        pending: true, lat: pos.coords.latitude, lng: pos.coords.longitude };
      this.local.update((l) => [...l, pending]);
      this.nearBottom = true;
      void this.deliver(pending);
    }, (err) => {
      this.locating.set(false);
      this.sendError.set(err.code === err.PERMISSION_DENIED
        ? 'Permita a localização no navegador para enviar onde você está.' : 'Não foi possível obter sua localização.');
    }, { enableHighAccuracy: true, timeout: 10_000, maximumAge: 10_000 });
  }

  protected retry(m: ChatMessage): void {
    this.local.update((l) => l.map((x) => (x.id === m.id ? { ...x, failed: false, pending: true } : x)));
    void this.deliver(m);
  }

  private async deliver(m: ChatMessage): Promise<void> {
    this.sendError.set('');
    try {
      await this.api.post(`/rides/${this.rideId()}/chat/messages`, m.type === 'LOCATION'
        ? { clientMessageId: m.clientMessageId, text: m.text, location: { lat: m.lat, lng: m.lng } }
        : { clientMessageId: m.clientMessageId, text: m.text });
    } catch (err) {
      const e = ApiError.from(err);
      this.local.update((l) => l.map((x) => (x.id === m.id ? { ...x, pending: false, failed: true } : x)));
      this.sendError.set(e.code === 'RATE_LIMITED' ? 'Aguarde um instante antes de enviar mais mensagens.' : e.message);
    }
  }

  protected async loadOlder(): Promise<void> {
    if (!this.olderCursor) return;
    this.loadingOlder.set(true);
    try {
      const page = await this.api.get<Page<ChatMessage>>(`/rides/${this.rideId()}/chat/messages`,
        { before: this.olderCursor, limit: 30 });
      this.older.update((o) => [...page.items, ...o]);
      this.olderCursor = page.nextCursor;
      this.hasOlder.set(!!page.nextCursor);
    } finally {
      this.loadingOlder.set(false);
    }
  }

  protected onScroll(): void {
    const el = this.scroller().nativeElement;
    this.nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (this.nearBottom) {
      this.newBelow.set(false);
      this.scheduleRead();
    }
  }

  scrollToBottom(smooth: boolean): void {
    const el = this.scroller()?.nativeElement;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    this.newBelow.set(false);
  }

  /** Confirma leitura (com atraso, apenas se há mensagem da outra pessoa e o chat está visível). */
  private scheduleRead(): void {
    if (!this.writable() && this.chatDoc()?.status !== 'READ_ONLY') return;
    const fromOther = this.live().some((m) => m.senderId !== this.myUid() && (m.type === 'TEXT' || m.type === 'LOCATION'));
    if (!fromOther || document.hidden || !this.nearBottom) return;
    clearTimeout(this.readTimer);
    this.readTimer = setTimeout(() => void this.api.post(`/rides/${this.rideId()}/chat/read`).catch(() => undefined), 800);
  }
}
