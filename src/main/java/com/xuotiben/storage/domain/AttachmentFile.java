package com.xuotiben.storage.domain;

import java.nio.file.Path;

public record AttachmentFile(Path path, String fileName, String mimeType, long byteSize) {}
