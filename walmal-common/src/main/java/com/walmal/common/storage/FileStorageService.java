package com.walmal.common.storage;

import java.io.InputStream;

public interface FileStorageService {
    StoredFile upload(String bucket, String key, InputStream content, String contentType, long size);
    InputStream download(String bucket, String key);
    void delete(String bucket, String key);
    String getPresignedUrl(String bucket, String key);
}
