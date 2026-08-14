package com.agent.util;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public final class TokenUtil {

    private TokenUtil() {
    }

    public static String createToken(Long userId, String username) {
        String raw = userId + ":" + username + ":" + Instant.now().toEpochMilli() + ":" + IdUtil.uuid();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
