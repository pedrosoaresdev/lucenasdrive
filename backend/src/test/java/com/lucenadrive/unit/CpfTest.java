package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.common.cpf.Cpf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** CPFs fictícios gerados apenas para teste (dígitos verificadores calculados). */
class CpfTest {

    @ParameterizedTest
    @ValueSource(strings = {"529.982.247-25", "52998224725", "111.444.777-35", "390.533.447-05"})
    void acceptsMathematicallyValidCpf(String cpf) {
        assertThat(Cpf.isValid(cpf)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"529.982.247-24", "111.111.111-11", "000.000.000-00", "123", "", "abc.def.ghi-jk",
            "5299822472", "529982247255"})
    void rejectsInvalidCpf(String cpf) {
        assertThat(Cpf.isValid(cpf)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(Cpf.isValid(null)).isFalse();
    }

    @Test
    void masksForDisplay() {
        assertThat(Cpf.mask("52998224725")).isEqualTo("***.982.247-**");
    }
}
