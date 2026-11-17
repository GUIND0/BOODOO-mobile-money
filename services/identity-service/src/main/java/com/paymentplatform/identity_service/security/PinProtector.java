package com.paymentplatform.identity_service.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class PinProtector {
    private final byte[] key;
    private final String version;
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    public PinProtector(PinProtectionProperties properties) {
        try { key = Base64.getDecoder().decode(properties.pepper()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("PIN pepper must be Base64-encoded random bytes"); }
        if (key.length < 32) throw new IllegalArgumentException("PIN pepper must contain at least 32 random bytes");
        version = properties.pepperVersion();
    }

    public String encode(String integrator, UUID customer, String pin) {
        String input = Base64.getEncoder().encodeToString(hmac("boodoo-pin-storage-v1", integrator, customer.toString(), pin));
        return encoder.encode(input);
    }

    public String fingerprint(String integrator, UUID customer, String pin) {
        return HexFormat.of().formatHex(hmac("boodoo-pin-idempotency-v1", integrator, customer.toString(), pin));
    }

    public String version() { return version; }

    private byte[] hmac(String... values) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Required PIN protection algorithm unavailable", error);
        }
    }
}
