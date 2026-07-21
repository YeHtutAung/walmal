package com.walmal.content.infrastructure;

import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentImageStorageAdapterTest {

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private ContentImageStorageAdapter adapter;

    @Captor
    private ArgumentCaptor<String> keyCaptor;

    @Test
    void should_uploadWithHomeSectionKey_when_storeCalled() {
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        long size = 3L;
        String contentType = "image/png";

        when(fileStorageService.upload(eq("content-images"), keyCaptor.capture(),
                eq(stream), eq(contentType), eq(size)))
                .thenAnswer(invocation -> new StoredFile(
                        invocation.getArgument(1), "content-images", contentType, size));

        String returnedKey = adapter.store("hero", "photo.png", stream, size, contentType);

        String capturedKey = keyCaptor.getValue();
        assertThat(capturedKey).matches("^home/hero/[0-9a-f\\-]{36}-photo\\.png$");
        assertThat(returnedKey).isEqualTo(capturedKey);
        verify(fileStorageService).upload("content-images", capturedKey, stream, contentType, size);
    }

    @Test
    void should_delegateToPresignedUrl_when_getUrlCalled() {
        when(fileStorageService.getPresignedUrl("content-images", "home/hero/some-key.png"))
                .thenReturn("https://minio.local/content-images/home/hero/some-key.png");

        String url = adapter.getUrl("home/hero/some-key.png");

        assertThat(url).isEqualTo("https://minio.local/content-images/home/hero/some-key.png");
        verify(fileStorageService).getPresignedUrl("content-images", "home/hero/some-key.png");
    }
}
