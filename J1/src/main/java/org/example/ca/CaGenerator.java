package org.example.ca;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.example.common.Pem;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Создание ключа подписи и самоподписанного сертификата удостоверяющего центра.
 * Ключ подписи должен быть создан заранее — сервер только читает его из файла.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CaGenerator {

    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** Пара «ключ подписи + самоподписанный сертификат» удостоверяющего центра. */
    public record Ca(KeyPair keyPair, X509Certificate certificate) {

        /** Записывает ключ и сертификат в PEM-файлы. */
        public void write(Path keyFile, Path certFile) throws IOException, GeneralSecurityException {
            Path keyParent = keyFile.toAbsolutePath().getParent();
            if (keyParent != null) {
                Files.createDirectories(keyParent);
            }
            Path certParent = certFile.toAbsolutePath().getParent();
            if (certParent != null) {
                Files.createDirectories(certParent);
            }
            Files.write(keyFile, Pem.encode(Pem.TYPE_PRIVATE_KEY, keyPair.getPrivate().getEncoded()));
            Files.write(certFile, Pem.encode(Pem.TYPE_CERTIFICATE, certificate.getEncoded()));
        }
    }

    /**
     * @param issuerName Issuer Name удостоверяющего центра (DN либо просто значение CN)
     * @param keySize    длина ключа подписи в битах
     * @param validity   срок действия сертификата УЦ
     */
    public static Ca generate(String issuerName, int keySize, Duration validity)
            throws GeneralSecurityException, OperatorCreationException, IOException {
        Providers.init();
        X500Name name = CertificateAuthority.parseName(issuerName);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                name,
                new BigInteger(128, new SecureRandom()),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(validity)),
                name,
                SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));

        JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                extensionUtils.createSubjectKeyIdentifier(keyPair.getPublic()));

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(Providers.BC)
                .build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(Providers.BC)
                .getCertificate(holder);
        return new Ca(keyPair, certificate);
    }
}
