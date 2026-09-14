package org.example.common;

import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Кодирование DER-структур в текстовый формат PEM. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Pem {

    public static final String TYPE_CERTIFICATE = "CERTIFICATE";
    public static final String TYPE_PRIVATE_KEY = "PRIVATE KEY";

    public static byte[] encode(String type, byte[] der) {
        StringWriter text = new StringWriter();
        try (PemWriter writer = new PemWriter(text)) {
            writer.writeObject(new PemObject(type, der));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось закодировать PEM " + type, e);
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
