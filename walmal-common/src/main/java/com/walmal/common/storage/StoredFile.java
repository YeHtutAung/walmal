package com.walmal.common.storage;

public record StoredFile(
    String key,
    String bucket,
    String contentType,
    long size
) {}
