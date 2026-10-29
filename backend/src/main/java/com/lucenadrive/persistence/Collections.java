package com.lucenadrive.persistence;

/** Nomes das coleções do Firestore. Documentados em docs/DATA_MODEL.md. */
public final class Collections {

    public static final String USERS = "users";
    public static final String USER_PRIVATE = "userPrivate";
    public static final String CPF_INDEX = "cpfIndex";
    public static final String PASSENGER_PROFILES = "passengerProfiles";
    public static final String DRIVER_PROFILES = "driverProfiles";
    public static final String DRIVER_PRIVATE = "driverPrivate";
    public static final String VEHICLES = "vehicles";
    public static final String PLATE_INDEX = "plateIndex";
    public static final String RIDES = "rides";
    public static final String RIDE_EVENTS = "rideEvents";
    public static final String RIDE_OFFERS = "rideOffers";
    public static final String RIDE_DISPATCH = "rideDispatch";
    public static final String FARE_QUOTES = "fareQuotes";
    public static final String DRIVER_LOCATIONS = "driverLocations";
    public static final String RIDE_CHATS = "rideChats";
    public static final String RATINGS = "ratings";
    public static final String CITIES = "cities";
    public static final String SERVICE_AREAS = "serviceAreas";
    public static final String FARE_CONFIGS = "fareConfigs";
    public static final String FARE_CONFIG_VERSIONS = "fareConfigVersions";
    public static final String COUPONS = "coupons";
    public static final String COUPON_REDEMPTIONS = "couponRedemptions";
    public static final String COUPON_USAGE = "couponUsage";
    public static final String PAYMENT_TRANSACTIONS = "paymentTransactions";
    public static final String LEDGER_ENTRIES = "ledgerEntries";
    public static final String PAYOUT_REQUESTS = "payoutRequests";
    public static final String PIX_KEYS = "pixKeys";
    public static final String WEBHOOK_EVENTS = "webhookEvents";
    public static final String PLATFORM_SETTINGS = "platformSettings";
    public static final String ADMIN_AUDIT_LOGS = "adminAuditLogs";
    public static final String SECURITY_EVENTS = "securityEvents";
    public static final String ADMIN_SECURITY = "adminSecurity";
    public static final String ADMIN_SESSIONS = "adminSessions";
    public static final String SUPPORT_TICKETS = "supportTickets";
    public static final String NOTIFICATIONS = "notifications";
    public static final String PUSH_TOKENS = "pushTokens";
    public static final String IDEMPOTENCY_KEYS = "idempotencyKeys";
    public static final String PRIVACY_REQUESTS = "privacyRequests";
    public static final String SCHEDULER_LOCKS = "schedulerLocks";
    public static final String RIDE_CHAT_ARCHIVES = "rideChatArchives";
    public static final String RIDE_SHARES = "rideShares";

    private Collections() {
    }

    public static String messages(String rideId) {
        return RIDE_CHATS + "/" + rideId + "/messages";
    }

    public static String ticketMessages(String ticketId) {
        return SUPPORT_TICKETS + "/" + ticketId + "/messages";
    }
}
