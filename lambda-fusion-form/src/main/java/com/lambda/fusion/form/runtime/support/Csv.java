package com.lambda.fusion.form.runtime.support;

import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;
import java.io.BufferedReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 解析适配（hutool，{@code DynamicDataServiceImpl} 导出格式的逆操作）
 *
 * <p>引号转义、字段内逗号/换行由 hutool 按 RFC 4180 处理；hutool 对 Reader 输入不做
 * 编码探测，导出写入的 UTF-8 BOM 在此统一剥离。
 *
 * @author Zx
 */
public final class Csv {

    private static final char BOM = '﻿';

    private Csv() {}

    public static List<List<String>> parse(Reader reader) {
        BufferedReader buffered =
                reader instanceof BufferedReader bufferedReader ? bufferedReader : new BufferedReader(reader);
        try {
            buffered.mark(1);
            int first = buffered.read();
            if (first != -1 && first != BOM) {
                buffered.reset();
            }
            List<CsvRow> rows = CsvUtil.getReader().read(buffered).getRows();
            List<List<String>> result = new ArrayList<>(rows.size());
            for (CsvRow row : rows) {
                result.add(new ArrayList<>(row.getRawList()));
            }
            return result;
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("CSV 读取失败：" + error.getMessage(), error);
        }
    }
}
