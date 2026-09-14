package org.example.ca;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.security.Security;

/** Однократная регистрация провайдера BouncyCastle. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Providers {

    public static final String BC = BouncyCastleProvider.PROVIDER_NAME;

    public static synchronized void init() {
        if (Security.getProvider(BC) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
}
