package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Nada de descrição da API, métricas ou documentação aberta fora do ambiente de desenvolvimento. */
class ExposureIT extends IntegrationTestBase {

    private int status(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse().getStatus();
    }

    @Test
    void internalEndpointsAreNotPublic() throws Exception {
        assertThat(status("/v3/api-docs")).isIn(401, 403, 404);
        assertThat(status("/swagger-ui.html")).isIn(401, 403, 404);
        assertThat(status("/actuator/metrics")).isIn(401, 403, 404);
        assertThat(status("/actuator/prometheus")).isIn(401, 403, 404);
        assertThat(status("/actuator/env")).isIn(401, 403, 404);
        assertThat(status("/actuator/health")).isEqualTo(200);
    }
}
