package com.github.catvod.utils;

import java.util.regex.Pattern;

public final class SecretRedactor {

    private static final String MASK = "$1[REDACTED]$3";
    private static final String JSON_QUOTED_MASK = "$1$2[REDACTED]$3";
    private static final Pattern JSON_QUOTED = Pattern.compile("(?i)([\\\"]?(?:cookie|authorization|refresh[_-]?token|access[_-]?token|open[_-]?token|token|credential|password|secret)[\\\"]?\\s*[:=]\\s*)([\\\"])[^\\\"]*([\\\"])");
    private static final Pattern JSON_PLAIN = Pattern.compile("(?i)([\\\"]?(?:cookie|authorization|refresh[_-]?token|access[_-]?token|open[_-]?token|token|credential|password|secret)[\\\"]?\\s*[:=]\\s*)([^\\\",}\\s]+)");
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
        // Matcher.replaceAll(Function) is unavailable on Android 9 (API 28). Split quoted and
        // plain values so the long-supported String overload can preserve quotes and delimiters.
        result = JSON_QUOTED.matcher(result).replaceAll(JSON_QUOTED_MASK);
        result = JSON_PLAIN.matcher(result).replaceAll("$1[REDACTED]");
        return HEADER.matcher(result).replaceAll(MASK);
    }
}
