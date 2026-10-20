package io.github.bakic.springaiinsights.identity;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Replaces raw user IDs with HMAC-SHA256(secret, id) as 64 lower-case hex characters. */
public final class Pseudonymiser {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public Pseudonymiser(byte[] secret) {
        if (secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
    }

    public static Pseudonymiser withRandomSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new Pseudonymiser(secret);
    }

    public String pseudonymise(String raw) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(UTF_8)));
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
