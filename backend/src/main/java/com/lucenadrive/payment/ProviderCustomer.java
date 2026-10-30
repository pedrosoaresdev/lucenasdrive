package com.lucenadrive.payment;

import java.time.Instant;

/** providerCustomers/{provider}_{uid}: cliente correspondente no provedor (evita cadastro duplicado). */
public record ProviderCustomer(String id, String provider, String userId, String customerId, Instant createdAt) {
}
