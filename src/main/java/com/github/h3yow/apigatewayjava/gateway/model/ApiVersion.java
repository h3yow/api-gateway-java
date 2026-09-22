package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 语义化接口版本（major.minor）。
 * 接受的网络表达：{@code v1.0}、{@code V1.0}、{@code 1.0}（首尾空白会被去除）。
 * 无法解析的输入抛出 {@link IllegalArgumentException}，由版本协商层映射为明确拒绝，
 * 本类自身绝不做默认版本回落。
 */
public record ApiVersion(int major, int minor) implements Comparable<ApiVersion> {

    public ApiVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("版本号不能为负数: " + major + "." + minor);
        }
    }

    public static ApiVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("版本为空");
        }
        String value = raw.trim();
        if (value.charAt(0) == 'v' || value.charAt(0) == 'V') {
            value = value.substring(1);
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("无法识别的版本: " + raw);
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return new ApiVersion(major, minor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法识别的版本: " + raw, e);
        }
    }

    public String httpValue() {
        return "v" + major + "." + minor;
    }

    @Override
    public int compareTo(ApiVersion other) {
        int cmp = Integer.compare(major, other.major);
        return cmp != 0 ? cmp : Integer.compare(minor, other.minor);
    }
}
