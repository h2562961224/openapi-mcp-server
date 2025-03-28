package com.agent.bookkeeper.application.base.mcp;

/**
 * @author chuzhen
 * @since 2025/3/28 15:30
 */
public interface UserContextSetter {

    void set(String auth);

    void clear();
}
