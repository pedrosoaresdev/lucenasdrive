package com.lucenadrive.user;

/** Preferências de notificação. Alertas de segurança são sempre enviados. */
public record NotificationPreferences(boolean rideUpdates, boolean chatMessages, boolean promotions,
                                      boolean financial, boolean push, boolean hideContentOnLockScreen) {

    public static NotificationPreferences defaults() {
        return new NotificationPreferences(true, true, false, true, true, true);
    }
}
