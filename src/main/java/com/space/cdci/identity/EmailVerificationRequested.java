package com.space.cdci.identity;

public record EmailVerificationRequested(String recipient, String verificationUrl) {}
