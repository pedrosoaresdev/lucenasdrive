package com.lucenadrive.notification;

/** Tipos de notificação e a preferência que os controla. */
public enum NotificationType {
    ACCOUNT_CONFIRMED(Category.ACCOUNT),
    DOCUMENT_REVIEWED(Category.ACCOUNT),
    DRIVER_APPROVED(Category.ACCOUNT),
    DRIVER_STATUS_CHANGED(Category.ACCOUNT),
    RIDE_REQUESTED(Category.RIDE),
    RIDE_OFFER(Category.RIDE),
    RIDE_ACCEPTED(Category.RIDE),
    DRIVER_ARRIVED(Category.RIDE),
    RIDE_STARTED(Category.RIDE),
    RIDE_COMPLETED(Category.RIDE),
    RIDE_CANCELLED(Category.RIDE),
    CHAT_MESSAGE(Category.CHAT),
    RATING_PENDING(Category.RIDE),
    PAYMENT_CONFIRMED(Category.FINANCIAL),
    REFUND(Category.FINANCIAL),
    PAYOUT(Category.FINANCIAL),
    FINANCIAL_FAILURE(Category.FINANCIAL),
    SECURITY_CHANGE(Category.SECURITY),
    PROMOTION(Category.PROMOTION),
    SUPPORT_UPDATE(Category.ACCOUNT);

    public enum Category { ACCOUNT, RIDE, CHAT, FINANCIAL, SECURITY, PROMOTION }

    private final Category category;

    NotificationType(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
