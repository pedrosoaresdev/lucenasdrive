/**
 * Homologação no projeto Firebase "lucenasdrive" (dados de TESTE, nunca de clientes reais).
 *
 * Cole aqui os valores do Console Firebase › Configurações do projeto › Seus apps › App da Web
 * (bloco firebaseConfig). São identificadores PÚBLICOS: a proteção vem das regras do Firestore/Storage,
 * da validação no servidor e, depois, do App Check. NUNCA coloque aqui a conta de serviço (JSON).
 */
export const HOMOLOG_FIREBASE = {
  apiKey: 'COLE_AQUI_apiKey',
  authDomain: 'lucenasdrive.firebaseapp.com',
  projectId: 'lucenasdrive',
  storageBucket: 'COLE_AQUI_storageBucket',
  appId: 'COLE_AQUI_appId',
  messagingSenderId: 'COLE_AQUI_messagingSenderId',
};

/** Console Firebase › Cloud Messaging › Certificados push da Web (chave pública). Vazio = push desativado. */
export const HOMOLOG_FCM_VAPID_KEY = '';

/** Chave de NAVEGADOR do Google Maps restrita a http://localhost:* e http://127.0.0.1:*. Vazio = sem mapa. */
export const HOMOLOG_MAPS_BROWSER_KEY = '';
