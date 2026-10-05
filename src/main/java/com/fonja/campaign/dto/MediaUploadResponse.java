package com.fonja.campaign.dto;

/**
 * @param url the path to put in {@code coverMediaPath} or an image's {@code filePath}, and to use
 *     directly as an {@code <img src>}. Relative to the API origin so it survives a domain change
 */
public record MediaUploadResponse(Long id, String url, String contentType) {}
