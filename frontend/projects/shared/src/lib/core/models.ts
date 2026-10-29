/** Tipos espelhando os DTOs da API (backend Spring Boot). Valores monetários em centavos. */

export type Role = 'PASSENGER' | 'DRIVER' | 'SUPPORT' | 'FINANCE' | 'ADMIN' | 'OWNER';
export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'PENDING_DELETION' | 'DELETED';
export type VehicleCategory = 'ESSENCIAL' | 'CONFORTO' | 'EXECUTIVO';
export type PaymentMethod = 'CASH' | 'PIX' | 'CARD';
export type RideStatus =
  | 'REQUESTED' | 'SEARCHING_DRIVER' | 'ACCEPTED' | 'DRIVER_ARRIVING'
  | 'DRIVER_ARRIVED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type DriverStatus = 'INCOMPLETE' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'SUSPENDED' | 'DOCUMENTS_EXPIRED';

export interface ApiErrorBody {
  code: string;
  message: string;
  status: number;
  path?: string;
  traceId?: string;
  fieldErrors?: { field: string; message: string }[];
  details?: Record<string, unknown>;
}

export interface Page<T> { items: T[]; nextCursor: string | null; }

export interface NotificationPreferences {
  rideUpdates: boolean; chatMessages: boolean; promotions: boolean; financial: boolean;
  push: boolean; hideContentOnLockScreen: boolean;
}

export interface Me {
  uid: string; email: string; emailVerified: boolean; phoneNumber: string | null; phoneVerified: boolean;
  registered: boolean; fullName: string | null; photoUrl: string | null; roles: Role[]; status: UserStatus;
  cpfMasked: string | null; notificationPreferences: NotificationPreferences; driverStatus: DriverStatus | null;
}

export interface Place { lat: number; lng: number; address?: string; placeId?: string; }

export interface FavoritePlace { kind: 'HOME' | 'WORK' | 'OTHER'; label: string; place: Place; }

export interface PassengerProfile {
  id: string; favorites: FavoritePlace[]; recentDestinations: Place[]; completedRides: number; cancelledRides: number;
}

export interface RouteInfo { distanceMeters: number; durationSeconds: number; encodedPolyline?: string; source: string; }

export interface Quote {
  quoteId: string; category: VehicleCategory; categoryName: string; totalCents: number; discountCents: number;
  finalCents: number; minimumApplied: boolean; multiplierBp: number; couponCode: string | null;
  couponMessage: string | null; availableDrivers: number; expiresAt: string;
}

export interface Estimate { cityId: string; cityName: string; route: RouteInfo; approximateRoute: boolean; quotes: Quote[]; }

export interface DriverSnapshot {
  userId: string; name: string; photoPath: string | null; ratingAvg: number; ratingCount: number; vehicleId: string;
  vehicleMake: string; vehicleModel: string; vehicleColor: string; plate: string; vehicleYear: number;
}

export interface FareView {
  totalCents: number; discountCents: number; finalCents: number; commissionCents: number | null;
  driverEarningCents: number | null; cancellationFeeCents: number; minimumApplied: boolean; multiplierBp: number;
  fareConfigId: string; fareConfigVersion: number;
}

/** Instantes: a API retorna ISO-8601; documentos do Firestore trazem epoch em ms. */
export type Instantish = string | number | null | undefined;

export interface Timeline {
  requestedAt: Instantish; acceptedAt: Instantish; arrivingAt: Instantish; arrivedAt: Instantish;
  startedAt: Instantish; completedAt: Instantish; cancelledAt: Instantish;
}

export interface RideView {
  id: string; code: string; status: RideStatus; category: VehicleCategory; categoryName?: string;
  origin: Place; destination: Place; route: RouteInfo; fare: FareView; couponCode: string | null;
  paymentMethod: PaymentMethod; paymentStatus: string;
  passenger: { userId: string; name: string; photoPath: string | null; rating: number | null } | null;
  driver: DriverSnapshot | null;
  driverPosition: { lat: number; lng: number; heading?: number; updatedAt: Instantish } | null;
  etaSeconds: number | null; timeline: Timeline;
  cancellation: { byUserId: string; byRole: string; reasonCode: string; reasonText: string; feeCents: number } | null;
  rating: { status: 'NOT_APPLICABLE' | 'PENDING' | 'SUBMITTED' | 'SKIPPED'; stars: number | null } | null;
  /** avaliação recíproca (motorista → passageiro); só o estado, nunca a nota */
  driverRating?: { status: 'NOT_APPLICABLE' | 'PENDING' | 'SUBMITTED' | 'SKIPPED'; stars: null } | null;
  chatAvailable?: boolean; createdAt: Instantish;
  /** presente no documento do Firestore */
  participantIds?: string[];
}

export interface ChatMessage {
  id: string; rideId: string; senderId: string; senderRole: 'PASSENGER' | 'DRIVER' | 'SYSTEM';
  type: 'TEXT' | 'SYSTEM'; text: string; systemEvent?: string; clientMessageId?: string; createdAt: Instantish;
  /** estado local do envio */
  pending?: boolean; failed?: boolean;
}

export interface RideChatDoc {
  id: string; participantIds: string[]; status: 'OPEN' | 'READ_ONLY' | 'CLOSED';
  lastReadAt?: Record<string, number>; passengerId: string; driverId: string;
}

export interface RideOffer {
  id: string; rideId: string; driverId: string; status: string; category: VehicleCategory;
  pickupAddress: string; destinationAddress: string; pickupLat: number; pickupLng: number;
  distanceToPickupMeters: number; tripDistanceMeters: number; tripDurationSeconds: number;
  estimatedEarningCents: number; passengerName: string; passengerRating: number | null; expiresAt: Instantish;
}

export interface AppNotification {
  id: string; userId: string; type: string; title: string; body: string; data: Record<string, string>;
  read: boolean; createdAt: Instantish;
}

export interface PublicConfig {
  routesApiConfigured: boolean; approximateRoutesOnly: boolean; paymentsConfigured: boolean;
  paymentMethods: PaymentMethod[]; categories: { id: VehicleCategory; name: string; seats: number }[];
  cities: { id: string; name: string; state: string; lat: number; lng: number }[];
  ratingTags: string[]; termsVersion: string; privacyVersion: string;
}

export interface SupportTicket {
  id: string; category: string; priority: string; status: string; rideId: string | null; subject: string;
  description: string; createdAt: Instantish; updatedAt: Instantish; userId: string; userRole: string;
  history: { status: string; byUserId: string; note: string; at: Instantish }[];
  attachmentPaths?: string[];
  metadata?: Record<string, string>;
}

export interface TicketMessage { id: string; authorId: string; fromStaff: boolean; text: string; createdAt: Instantish; }

export interface LedgerEntry {
  id: string; type: string; account: string; direction: 'CREDIT' | 'DEBIT'; amountCents: number; status: string;
  rideId: string | null; description: string; createdAt: Instantish; externalRef: string | null;
}

export interface PayoutRequest {
  id: string; amountCents: number; status: string; pixKeyMasked: string; providerTransferId: string | null;
  receiptUrl: string | null; failureReason: string | null; createdAt: Instantish; ownerType: string;
}
