package com.walmal.content.application.dto;

/**
 * Result of a home-page content image upload: the URL under which the stored image
 * can be referenced from a {@code HomeContent} document.
 */
public record ContentImageDto(String imageUrl) {}
