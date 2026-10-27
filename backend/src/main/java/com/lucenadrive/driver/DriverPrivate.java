package com.lucenadrive.driver;

import java.time.Instant;

/** driverPrivate/{uid}: número completo da CNH, criptografado. Sem acesso pelo cliente. */
public record DriverPrivate(String id, String cnhNumberEncrypted, Instant createdAt) {
}
