package com.minidb.common;

/** 列的数据类型。VARCHAR 长度由 ColumnDef.maxLength 承载。
 *  BOOLEAN 可作列类型（TRUE/FALSE 字面量，定长 1B），也是表达式/比较结果的语义类型。
 *  NULL 仅作为 NULL 字面量的语义类型（D5 M0 冻结：Literal(null, NULL, pos)），不能用作列类型。 */
public enum DataType {
    INT, FLOAT, VARCHAR, BOOLEAN, NULL
}
