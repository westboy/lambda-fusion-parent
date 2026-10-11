package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CSV 解析（导出的逆操作）：引号转义、字段内逗号/换行、BOM 剥离与行尾空字段保留。 */
class CsvTest {

    @Test
    void parsesQuotedCommasEscapedQuotesAndCrlf() throws IOException {
        List<List<String>> rows = Csv.parse(new StringReader("a,b\r\n\"x,1\",\"he said \"\"hi\"\"\"\r\n"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("a", "b");
        assertThat(rows.get(1)).containsExactly("x,1", "he said \"hi\"");
    }

    /** 导出文件首字符为 UTF-8 BOM：用常量构造（避免源文件内嵌不可见字符） */
    private static final String BOM = String.valueOf((char) 0xFEFF);

    @Test
    void stripsBomAndPreservesTrailingEmptyField() throws IOException {
        List<List<String>> rows = Csv.parse(new StringReader(BOM + "name,remark\n张三,\r\n"));

        assertThat(rows.get(0)).containsExactly("name", "remark");
        assertThat(rows.get(1)).containsExactly("张三", "");
    }

    @Test
    void keepsEmbeddedNewlineInsideQuotes() throws IOException {
        List<List<String>> rows = Csv.parse(new StringReader("a\n\"line1\nline2\"\n"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("line1\nline2");
    }

    @Test
    void skipsTrailingBlankLines() throws IOException {
        List<List<String>> rows = Csv.parse(new StringReader("a,b\n1,2\n\n"));

        assertThat(rows).hasSize(2);
    }
}
