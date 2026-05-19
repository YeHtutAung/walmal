package com.walmal.infrastructure.storage;

import com.walmal.common.storage.StoredFile;
import io.minio.*;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MinioFileStorageServiceTest {

    @Mock
    private MinioClient minioClient;

    @InjectMocks
    private MinioFileStorageService storageService;

    @Test
    void should_returnStoredFile_when_uploadSucceeds() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        InputStream content = new ByteArrayInputStream("test".getBytes());
        StoredFile result = storageService.upload("bucket", "key.txt", content, "text/plain", 4);

        assertThat(result.key()).isEqualTo("key.txt");
        assertThat(result.bucket()).isEqualTo("bucket");
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void should_createBucket_when_bucketDoesNotExist() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        InputStream content = new ByteArrayInputStream("test".getBytes());
        storageService.upload("new-bucket", "key.txt", content, "text/plain", 4);

        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    void should_callRemoveObject_when_deleteIsCalled() throws Exception {
        storageService.delete("bucket", "key.txt");

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }
}
