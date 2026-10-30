package com.lucenadrive.geo;

import java.util.List;
import java.util.Map;

/** Acesso de teste ao parser (pacote do provedor). */
public final class OsrmRouteProviderAccess {

    private OsrmRouteProviderAccess() {
    }

    public static List<Long> parseTable(Map<String, Object> res, int n) {
        return OsrmRouteProvider.parseTable(res, n);
    }
}
