package org.example.ca;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.example.common.KeyResult;
import org.example.common.Pem;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Удостоверяющий центр: хранит заранее созданный ключ подписи и выдаёт сертификаты X509
 * на публичные ключи клиентов. Объект неизменяем и используется всеми генерирующими
 * нитями одновременно.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CertificateAuthority {

    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final PrivateKey signingKey;
    private final X509Certificate caCertificate;
    private final X500Name issuer;
    private final Duration validity;
    private final SecureRandom random = new SecureRandom();

    /**
     * Читает ключ подписи и сертификат УЦ из файлов.
     *
     * @param issuerOverride Issuer Name из командной строки или конфигурационного файла;
     *                       если null или пусто, берётся Subject Name сертификата УЦ
     */
    public static CertificateAuthority load(Path keyFile, Path certFile, String issuerOverride, Duration validity)
            throws IOException, CertificateException {
        Providers.init();
        PrivateKey key = readPrivateKey(keyFile);
        X509Certificate certificate = readCertificate(certFile);
        return of(key, certificate, issuerOverride, validity);
    }

    /** Собирает УЦ из готовых ключа и сертификата (используется тестами). */
    public static CertificateAuthority of(PrivateKey signingKey, X509Certificate certificate, String issuerOverride, Duration validity)
            throws CertificateEncodingException {
        Providers.init();
        X500Name issuer = issuerOverride == null || issuerOverride.isBlank()
                ? new JcaX509CertificateHolder(certificate).getSubject()
                : parseName(issuerOverride);
        return new CertificateAuthority(signingKey, certificate, issuer, validity);
    }

    /**
     * Подписывает публичный ключ клиента: Subject Name совпадает с именем, переданным клиентом,
     * Issuer Name фиксирован настройками сервиса.
     *
     * @return PEM приватного ключа и PEM сертификата
     */
    public KeyResult issue(String subjectName, KeyPair keyPair)
            throws OperatorCreationException, GeneralSecurityException, IOException {
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, subjectName).build();
        Instant now = Instant.now();
        BigInteger serial = new BigInteger(128, random);
        SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());

        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                issuer,
                serial,
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(validity)),
                subject,
                publicKeyInfo);

        JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.nonRepudiation));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth}));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                extensionUtils.createSubjectKeyIdentifier(keyPair.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                extensionUtils.createAuthorityKeyIdentifier(caCertificate));

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(Providers.BC)
                .build(signingKey);
        X509CertificateHolder holder = builder.build(signer);

        byte[] certPem = Pem.encode(Pem.TYPE_CERTIFICATE, holder.getEncoded());
        byte[] keyPem = Pem.encode(Pem.TYPE_PRIVATE_KEY, keyPair.getPrivate().getEncoded());
        return new KeyResult(keyPem, certPem);
    }

    public X500Name issuer() {
        return issuer;
    }

    public X509Certificate certificate() {
        return caCertificate;
    }

    /** Разбирает строку как DN; если это не DN, считает её значением CN. */
    public static X500Name parseName(String value) {
        try {
            return new X500Name(value);
        } catch (RuntimeException e) {
            return new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, value).build();
        }
    }

    private static PrivateKey readPrivateKey(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file);
             PEMParser parser = new PEMParser(reader)) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(Providers.BC);
            if (object instanceof PEMKeyPair pemKeyPair) {
                return converter.getKeyPair(pemKeyPair).getPrivate();
            }
            if (object instanceof PrivateKeyInfo privateKeyInfo) {
                return converter.getPrivateKey(privateKeyInfo);
            }
            throw new IOException("Файл " + file + " не содержит приватного ключа в формате PEM");
        }
    }

    private static X509Certificate readCertificate(Path file) throws IOException, CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(file)) {
            return (X509Certificate) factory.generateCertificate(in);
        }
    }
}
