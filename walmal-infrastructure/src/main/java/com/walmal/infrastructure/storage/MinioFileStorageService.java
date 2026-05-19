package com.walmal.infrastructure.storage;

import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import io.minio.*;
import io.minio.http.Method;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
public class MinioFileStorageService implements FileStorageService {

    private final MinioClient minioClient;

    public MinioFileStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public StoredFile upload(String bucket, String key, InputStream content,
                             String contentType, long size) {
        try {
            ensureBucketExists(bucket);
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(content, size, -1)
                .contentType(contentType)
                .build());
            return new StoredFile(key, bucket, contentType, size);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file: " + key, e);
        }
    }

    @Override
    public InputStream download(String bucket, String key) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file: " + key, e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file: " + key, e);
        }
    }

    @Override
    public String getPresignedUrl(String bucket, String key) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(key)
                .expiry(1, TimeUnit.HOURS)
                .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL: " + key, e);
        }
    }

    private void ensureBucketExists(String bucket) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
