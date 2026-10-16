package com.cuotiben.profile.domain;

import java.nio.file.Path;

public record ProfileAvatar(Path path, String mimeType, long byteSize) {}
