import { LucenaConfig } from '@lucena/shared';

/**
 * Produção: valores PÚBLICOS do projeto Firebase/Google (não são segredos; a proteção vem das
 * regras do Firebase, do App Check e das restrições de chave no Google Cloud).
 * Substitua os marcadores no pipeline de build (ver docs/DEPLOY.md).
 */
export const environment: LucenaConfig = {
  portal: 'admin',
  apiBaseUrl: 'https://api.lucenadrive.example',
  firebase: {
    apiKey: '__FIREBASE_WEB_API_KEY__',
    authDomain: '__FIREBASE_PROJECT_ID__.firebaseapp.com',
    projectId: '__FIREBASE_PROJECT_ID__',
    storageBucket: '__FIREBASE_PROJECT_ID__.appspot.com',
    appId: '__FIREBASE_APP_ID__',
  },
  useEmulators: false,
  mapsBrowserKey: '',
};
