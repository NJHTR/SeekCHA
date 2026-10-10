package com.seekwd.dormcheck.security;

import java.nio.ByteBuffer;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CredentialCipher {
    private final SecretKeySpec key;

    public CredentialCipher(@Value("${dormcheck.encryption-key}") String encodedKey) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey);
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
        } catch (RuntimeException error) {
            throw new IllegalStateException("DORMCHECK_ENCRYPTION_KEY must be base64 of 32 random bytes", error);
        }
    }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] nonce = new byte[12];
            new java.security.SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return ByteBuffer.allocate(nonce.length + plain.length + 16).put(nonce).put(cipher.doFinal(plain)).array();
        } catch (Exception error) {
            throw new IllegalStateException("credential encryption failed", error);
        }
    }

    public byte[] decrypt(byte[] encrypted) {
        try {
            ByteBuffer input = ByteBuffer.wrap(encrypted);
            byte[] nonce = new byte[12];
            input.get(nonce);
            byte[] cipherText = new byte[input.remaining()];
            input.get(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return cipher.doFinal(cipherText);
        } catch (Exception error) {
            throw new IllegalStateException("credential decryption failed", error);
        }
    }
}
