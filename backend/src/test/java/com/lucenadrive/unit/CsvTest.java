package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.admin.reports.Csv;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void formatsForExcelAndBlocksFormulaInjection() {
        String out = new String(new Csv().row(List.of("a;b", "=SOMA(1;2)", "-10x", "@x", "linha\nnova", "aspas \"x\""))
                .row(List.of(Csv.money(123456), Csv.money(-5), Csv.decimal(2.345, 2))).bytes(), StandardCharsets.UTF_8);
        assertThat(out).startsWith("﻿");
        assertThat(out).contains("\"a;b\"").contains("\"'=SOMA(1;2)\"").contains("'-10x").contains("'@x")
                .contains("\"linha\nnova\"").contains("\"aspas \"\"x\"\"\"");
        assertThat(out).contains("1234,56;-0,05;2,35");
    }
}
