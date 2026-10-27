package com.lucenadrive.audit;

import com.lucenadrive.common.web.Ids;
import com.lucenadrive.common.web.PageResponse;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.persistence.Tx;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.RequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final DocStore store;
    private final Clock clock;

    public AuditService(DocStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public AuditLog build(AuthUser actor, String action, String targetType, String targetId,
                          Map<String, Object> before, Map<String, Object> after, String reason) {
        return new AuditLog(Ids.newId(), actor.uid(), actor.roles().stream().map(Enum::name).sorted().toList(),
                action, targetType, targetId, before, after, reason,
                RequestContext.clientIp(), RequestContext.userAgent(), Instant.now(clock));
    }

    public void record(AuthUser actor, String action, String targetType, String targetId,
                       Map<String, Object> before, Map<String, Object> after, String reason) {
        AuditLog log = build(actor, action, targetType, targetId, before, after, reason);
        store.set(Collections.ADMIN_AUDIT_LOGS, log.id(), log);
    }

    /** Grava o registro na mesma transação da operação auditada (tudo-ou-nada). */
    public void recordInTx(Tx tx, AuthUser actor, String action, String targetType, String targetId,
                           Map<String, Object> before, Map<String, Object> after, String reason) {
        AuditLog log = build(actor, action, targetType, targetId, before, after, reason);
        tx.set(Collections.ADMIN_AUDIT_LOGS, log.id(), log);
    }

    public PageResponse<AuditLog> list(String targetType, String targetId, String actorId, Long cursor, int limit) {
        Query q = Query.of(Collections.ADMIN_AUDIT_LOGS);
        if (targetType != null) {
            q.eq("targetType", targetType);
        }
        if (targetId != null) {
            q.eq("targetId", targetId);
        }
        if (actorId != null) {
            q.eq("actorId", actorId);
        }
        q.orderBy("at", Query.Direction.DESC).limit(limit);
        if (cursor != null) {
            q.startAfter(cursor);
        }
        List<AuditLog> items = store.query(q, AuditLog.class);
        return PageResponse.of(items, limit, a -> a.at().toEpochMilli());
    }
}
