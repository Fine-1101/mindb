package com.minidb.plan;

/** ORDER BY 计划级排序键：列名 + 方向。NULL 排序拍板：视为最小值（ASC 在前、DESC 在后）。 */
public record SortKey(String column, boolean asc) {
}
