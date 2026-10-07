package kr.goodman.carbridge.transport;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/** Per-installation phone identity, plus an explicitly enrolled vehicle certificate pin. */
public final class ProjectionIdentity {
    private static final String ALIAS = "goodman.car.projection.rsa.v1";
    private ProjectionIdentity() {}

    /** The pin must come from the selected vehicle's enrollment, never from an unconfirmed network peer. */
    public static synchronized SSLContext context(byte[] enrolledVehicleSha256)
            throws GeneralSecurityException, java.io.IOException {
        if (enrolledVehicleSha256 == null || enrolledVehicleSha256.length != 32)
            throw new GeneralSecurityException("Vehicle certificate must be enrolled first");
        return create(enrolledVehicleSha256.clone(), null);
    }

    /** Collects a certificate candidate for the selected physical USB link, then REJECTS the handshake. */
    public static synchronized SSLContext enrollmentProbe(java.util.function.Consumer<byte[]> candidate)
            throws GeneralSecurityException, java.io.IOException {
        return create(null, java.util.Objects.requireNonNull(candidate));
    }

    private static SSLContext create(byte[] pin, java.util.function.Consumer<byte[]> candidate)
            throws GeneralSecurityException, java.io.IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            long now = System.currentTimeMillis();
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=Goodman Car Mirror"))
                    .setCertificateSerialNumber(new BigInteger(128, new SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(new Date(now - 86400000L))
                    .setCertificateNotAfter(new Date(now + 10L * 365 * 86400000L))
                    .build());
            generator.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
        java.security.cert.Certificate[] certificates = store.getCertificateChain(ALIAS);
        if (key == null || !"RSA".equals(key.getAlgorithm()) || certificates == null || certificates.length == 0)
            throw new GeneralSecurityException("Stored projection identity is unavailable");
        X509Certificate[] chain = new X509Certificate[certificates.length];
        for (int index = 0; index < chain.length; index++) chain[index] = (X509Certificate) certificates[index];
        chain[0].checkValidity();
        X509ExtendedKeyManager keys = new X509ExtendedKeyManager() {
            private String alias(String type) { return "RSA".equals(type) ? ALIAS : null; }
            @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
            @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
            @Override public String[] getServerAliases(String type, Principal[] issuers) {
                return alias(type) == null ? null : new String[]{ALIAS};
            }
            @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return alias(type); }
            @Override public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) { return alias(type); }
            @Override public X509Certificate[] getCertificateChain(String alias) { return ALIAS.equals(alias) ? chain.clone() : null; }
            @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
        };
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkServerTrusted(X509Certificate[] peer, String authType) throws CertificateException {
                throw new CertificateException("Projection phone must act as TLS server");
            }
            @Override public void checkClientTrusted(X509Certificate[] peer, String authType) throws CertificateException {
                if (peer == null || peer.length == 0 || peer.length > 8) throw new CertificateException("Missing vehicle certificate");
                peer[0].checkValidity();
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(peer[0].getEncoded());
                    if (pin == null) {
                        candidate.accept(actual.clone());
                        throw new CertificateException("Vehicle enrollment approval required");
                    }
                    if (!MessageDigest.isEqual(pin, actual)) throw new CertificateException("Vehicle certificate changed");
                } catch (java.security.NoSuchAlgorithmException error) { throw new CertificateException(error); }
            }
        };
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(new KeyManager[]{keys}, new TrustManager[]{trust}, new SecureRandom());
        return context;
    }
}
