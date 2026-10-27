import { Injectable, inject } from '@angular/core';
import {
  DocumentData,
  QueryConstraint,
  collection,
  doc,
  onSnapshot,
  query,
} from 'firebase/firestore';
import { Observable } from 'rxjs';
import { FirebaseService } from './firebase.service';

/**
 * Listeners do Firestore como Observables. As regras de segurança garantem que cada usuário
 * só receba os próprios dados (corrida da qual participa, ofertas, mensagens, notificações).
 * O SDK reconecta automaticamente e reentrega o estado atual após quedas de conexão.
 */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private readonly fb = inject(FirebaseService);

  doc<T>(path: string): Observable<T | null> {
    return new Observable<T | null>((sub) =>
      onSnapshot(
        doc(this.fb.firestore, path),
        (snap) => sub.next(snap.exists() ? ({ id: snap.id, ...(snap.data() as DocumentData) } as T) : null),
        (err) => sub.error(err),
      ),
    );
  }

  query<T>(path: string, ...constraints: QueryConstraint[]): Observable<T[]> {
    return new Observable<T[]>((sub) =>
      onSnapshot(
        query(collection(this.fb.firestore, path), ...constraints),
        { includeMetadataChanges: false },
        (snap) => sub.next(snap.docs.map((d) => ({ id: d.id, ...(d.data() as DocumentData) }) as T)),
        (err) => sub.error(err),
      ),
    );
  }
}
