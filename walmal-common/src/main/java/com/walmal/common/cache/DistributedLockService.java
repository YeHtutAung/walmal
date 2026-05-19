package com.walmal.common.cache;

import java.time.Duration;
import java.util.function.Supplier;

public interface DistributedLockService {
    boolean tryLock(String key, Duration timeout);
    void unlock(String key);
    <T> T executeWithLock(String key, Duration timeout, Supplier<T> action);
}
