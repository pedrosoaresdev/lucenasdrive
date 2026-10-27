package com.lucenadrive.integration;

import java.util.List;
import java.util.Map;

/** Envio de notificações push (FCM). Conteúdo deve ser genérico — sem dados sensíveis. */
public interface PushSender {

    /** @return tokens inválidos que devem ser removidos. */
    List<String> send(List<String> tokens, String title, String body, Map<String, String> data);
}
