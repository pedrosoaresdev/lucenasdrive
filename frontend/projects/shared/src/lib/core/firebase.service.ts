import { Injectable, inject } from '@angular/core';
import { FirebaseApp, initializeApp } from 'firebase/app';
import { Auth, browserLocalPersistence, connectAuthEmulator, getAuth, setPersistence } from 'firebase/auth';
import { Firestore, connectFirestoreEmulator, getFirestore } from 'firebase/firestore';
import { FirebaseStorage, connectStorageEmulator, getStorage } from 'firebase/storage';
import { LUCENA_CONFIG } from './config';

/** Inicializa o SDK cliente do Firebase uma única vez (com emuladores em desenvolvimento). */
@Injectable({ providedIn: 'root' })
export class FirebaseService {
  private readonly config = inject(LUCENA_CONFIG);
  readonly app: FirebaseApp;
  readonly auth: Auth;
  readonly firestore: Firestore;
  readonly storage: FirebaseStorage;

  constructor() {
    this.app = initializeApp(this.config.firebase, 'lucena-' + this.config.portal);
    this.auth = getAuth(this.app);
    this.firestore = getFirestore(this.app);
    this.storage = getStorage(this.app);
    void setPersistence(this.auth, browserLocalPersistence);
    this.auth.languageCode = 'pt-BR';
    if (this.config.useEmulators) {
      const host = this.config.emulatorHost ?? '127.0.0.1';
      connectAuthEmulator(this.auth, `http://${host}:9099`, { disableWarnings: true });
      connectFirestoreEmulator(this.firestore, host, 8085);
      connectStorageEmulator(this.storage, host, 9199);
    }
  }
}
