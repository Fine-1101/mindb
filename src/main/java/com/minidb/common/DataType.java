package com.minidb.common;

/** 列的数据类型。VARCHAR 长度由 ColumnDef.maxLength 承载。
 *  BOOLEAN 仅作为表达式/比较结果的语义类型（TypeRules、WHERE 检查），不能用作列类型。 */
public enum DataType {
    INT, FLOAT, VARCHAR, BOOLEAN
}
