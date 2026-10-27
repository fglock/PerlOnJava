package org.perlonjava.runtime.perlmodule;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class HttpTinySystemCaTest {
    @Test
    @Tag("unit")
    void verifiedTlsCombinesCertificatesFromMultipleSystemBundles() throws Exception {
        Path firstBundle = Files.createTempFile("perlonjava-test-ca-first", ".pem");
        Path secondBundle = Files.createTempFile("perlonjava-test-ca-second", ".pem");
        try {
            copyResource("/module/Net-SSLeay/t/data/root-ca.cert.pem", firstBundle);
            copyResource("/module/Net-SSLeay/t/data/simple-cert.cert.pem", secondBundle);

            CertificateFactory certificates = CertificateFactory.getInstance("X.509");
            X509Certificate certificate;
            try (InputStream input = Files.newInputStream(secondBundle)) {
                certificate = (X509Certificate) certificates.generateCertificate(input);
            }
            TrustManager[] managers = HttpTiny.createSystemCaTrustManagers(
                    java.util.List.of(firstBundle, secondBundle));
            assertNotNull(managers, "trust managers combine all readable PEM bundles");
            X509TrustManager trustManager = Arrays.stream(managers)
                    .filter(X509TrustManager.class::isInstance)
                    .map(X509TrustManager.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertTrue(trustManager instanceof X509ExtendedTrustManager,
                    "combined trust manager preserves socket and engine validation callbacks");

            assertDoesNotThrow(() -> trustManager.checkServerTrusted(
                    new X509Certificate[] { certificate }, certificate.getPublicKey().getAlgorithm()));
        } finally {
            Files.deleteIfExists(firstBundle);
            Files.deleteIfExists(secondBundle);
        }
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
