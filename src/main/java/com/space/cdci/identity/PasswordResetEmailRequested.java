package com.space.cdci.identity;

public record PasswordResetEmailRequested(String recipient, String resetUrl, long lifetimeMinutes) {}
