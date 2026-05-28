package com.walmal.infrastructure.storage;

import com.walmal.common.exception.WalmalException;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import io.minio.*;
import io.minio.messages.NotificationRecords;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

@Service
public class MinioFileStorageService implements FileStorageService {

    private final MinioClient minioClient;
    private final String publicUrl;

    public MinioFileStorageService(
            MinioClient minioClient,
            @Value("${walmal.minio.public-url}") String publicUrl) {
        this.minioClient = minioClient;
        this.publicUrl = publicUrl;
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
            throw new WalmalException("Failed to upload file: " + key, e);
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
            throw new WalmalException("Failed to download file: " + key, e);
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
            throw new WalmalException("Failed to delete file: " + key, e);
        }
    }

    /**
     * Returns a direct public URL for the object.
     * The bucket must have a public-read policy (set in {@link #ensureBucketExists}).
     */
    @Override
    public String getPresignedUrl(String bucket, String key) {
        String encodedKey = Arrays.stream(key.split("/", -1))
                .map(seg -> URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        return publicUrl + "/" + bucket + "/" + encodedKey;
    }

    private void ensureBucketExists(String bucket) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        setPublicReadPolicy(bucket);
    }

    private void setPublicReadPolicy(String bucket) throws Exception {
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": {"AWS": ["*"]},
                    "Action": ["s3:GetObject"],
                    "Resource": ["arn:aws:s3:::%s/*"]
                  }]
                }
                """.formatted(bucket);
        minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                .bucket(bucket)
                .config(policy)
                .build());
    }
}
