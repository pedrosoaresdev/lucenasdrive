import { LucenaConfig } from '@lucena/shared';

/** Desenvolvimento local com Firebase Emulator Suite (projeto demo, sem credenciais reais). */
export const environment: LucenaConfig = {
  portal: 'driver',
  apiBaseUrl: 'http://localhost:8080',
  firebase: {
    apiKey: 'demo-api-key',
    authDomain: 'demo-lucena-drive.firebaseapp.com',
    projectId: 'demo-lucena-drive',
    storageBucket: 'demo-lucena-drive.appspot.com',
    appId: 'demo-app-id',
  },
  useEmulators: true,
  emulatorHost: '127.0.0.1',
  // Cole aqui uma chave de NAVEGADOR restrita a http://localhost:* para ver mapas e busca de endereços.
  mapsBrowserKey: '',
};
