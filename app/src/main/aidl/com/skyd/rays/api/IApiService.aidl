package com.skyd.rays.api;

interface IApiService {
    // Legacy response: startIndex, size, data (uri, sticker, tags).
    String searchStickers(String requestPackage, String keyword, int startIndex, int size);

    // Paged response additionally includes nextIndex and each item's mimeType.
    // Append methods: existing transaction IDs must remain unchanged.
    String searchStickersPage(String requestPackage, String keyword, int startIndex, int size);
}
