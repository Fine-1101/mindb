package com.minidb.engine;

import com.minidb.common.MiniDbException;

/** 火山模型执行器接口：open → next(逐行) → close。 */
public interface Executor {
    void open() throws MiniDbException;

    /** 返回下一行数据，无更多行返回 null。 */
    Object[] next() throws MiniDbException;

    void close();
}
