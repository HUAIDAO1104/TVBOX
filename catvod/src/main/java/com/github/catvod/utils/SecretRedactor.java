package com.github.catvod.utils;

import java.util.regex.Pattern;

public final class SecretRedactor {

    private static final String MASK = "$1[REDACTED]$3";
    private static final Pattern JSON = Pattern.compile("(?i)([\\\"]?(?:cookie|authorization|refresh[_-]?token|access[_-]?token|open[_-]?token|token|credential|password|secret)[\\\"]?\\s*[:=]\\s*)([\\\"]?)([^\\\",}\\s]+|[^\\\"]*)(\\\"]?)");
    private static final Pattern HEADER = Pattern.compile("(?i)((?:cookie|authorization|refresh[_-]?token|access[_-]?token|open[_-]?token|token|credential|password|secret)\\s*[:=]\\s*)([^,;\\s}]+)([,;\\s}]|$)");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)([a-z0-9._~+/-]+=*)");
    private static final Pattern QUERY = Pattern.compile("(?i)([?&](?:cookie|token|access_token|refresh_token|open_token|opentoken|authorization|credential|password)=)([^&#\\s]+)");
    private static final Pattern USER_INFO = Pattern.compile("(?i)(https?://[^/@\\s:]+:)([^/@\\s]+)(@)");

    private SecretRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        String result = BEARER.matcher(value).replaceAll("$1[REDACTED]");
        result = USER_INFO.matcher(result).replaceAll("$1[REDACTED]$3");
        result = QUERY.matcher(result).replaceAll("$1[REDACTED]");
        result = JSON.matcher(result).replaceAll(match -> match.group(1) + match.group(2) + "[REDACTED]" + match.group(4));
        return HEADER.matcher(result).replaceAll(MASK);
    }
}
