import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import localePt from '@angular/common/locales/pt';
import { EnvironmentProviders, LOCALE_ID, Provider, inject, makeEnvironmentProviders, provideAppInitializer } from '@angular/core';
import { PushService } from './push.service';
import { authInterceptor } from './api.service';
import { LUCENA_CONFIG, LucenaConfig } from './config';

/** Provedores comuns aos três portais. */
export function provideLucena(config: LucenaConfig): EnvironmentProviders {
  registerLocaleData(localePt);
  const providers: Provider[] = [
    { provide: LUCENA_CONFIG, useValue: config },
    { provide: LOCALE_ID, useValue: 'pt-BR' },
  ];
  return makeEnvironmentProviders([...providers, provideHttpClient(withInterceptors([authInterceptor])),
    // Lê o estado do push sem pedir permissão (o pedido só ocorre por ação do usuário).
    provideAppInitializer(() => { void inject(PushService).refresh(); })]);
}
