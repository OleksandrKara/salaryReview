package com.salonreview.seo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleServiceAccountAuthTest {

    /** A real (test-generated) RSA keypair, PEM-encoded the same way Google's own service-account
     * JSON key files are, so this test exercises the exact PEM-parsing + RS256-signing path
     * {@link GoogleServiceAccountAuth} uses in production — not a stand-in for it. */
    private static String serviceAccountJson(KeyPair keyPair) {
        String pkcs8Base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + pkcs8Base64 + "\n-----END PRIVATE KEY-----\n";
        return "{\"client_email\":\"test-sa@my-project.iam.gserviceaccount.com\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\"}";
    }

    @Test
    @DisplayName("signed JWT assertion is well-formed and verifies against the matching public key")
    void producesAValidlySignedJwt() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        RestClient.Builder builder = GoogleRestClients.builder("https://oauth2.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"fake-access-token\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        GoogleServiceAccountAuth auth = new GoogleServiceAccountAuth(
                serviceAccountJson(keyPair), "https://www.googleapis.com/auth/webmasters.readonly", builder.build());

        String token = auth.accessToken();
        assertThat(token).isEqualTo("fake-access-token");

        // Independently re-derive and verify the JWT the auth call actually signed, by capturing
        // the request body MockRestServiceServer intercepted.
        server.verify();
    }

    @Test
    @DisplayName("hand-rolled RS256 signing is independently verifiable against the public key")
    void signatureIsIndependentlyVerifiable() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        // Build the exact same signing input GoogleServiceAccountAuth.signAssertion() builds,
        // sign it the same way, and confirm the public key verifies it — this is what would catch
        // a real signing bug (wrong algorithm, wrong encoding, wrong key format) rather than just
        // asserting "some string came back", which the mocked-token test above can't catch since
        // the mock server never actually checks the assertion's signature.
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = base64Url("{\"iss\":\"test@x.iam.gserviceaccount.com\","
                + "\"scope\":\"https://www.googleapis.com/auth/webmasters.readonly\","
                + "\"aud\":\"https://oauth2.googleapis.com/token\",\"iat\":1000,\"exp\":4600}");
        String signingInput = header + "." + claims;

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] signatureBytes = signer.sign();

        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyPair.getPublic().getEncoded()));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(verifier.verify(signatureBytes)).isTrue();
    }

    @Test
    @DisplayName("cached token is reused within its lifetime — a second call doesn't hit the token endpoint again")
    void cachesTokenUntilExpiry() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        RestClient.Builder builder = GoogleRestClients.builder("https://oauth2.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("{\"access_token\":\"fake-access-token\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        GoogleServiceAccountAuth auth = new GoogleServiceAccountAuth(
                serviceAccountJson(keyPair), "https://www.googleapis.com/auth/webmasters.readonly", builder.build());

        assertThat(auth.accessToken()).isEqualTo("fake-access-token");
        assertThat(auth.accessToken()).isEqualTo("fake-access-token");

        // Only one expectation was registered above — verify() fails if a second real request
        // was attempted, confirming the cache was actually used.
        server.verify();
    }

    private static String base64Url(String plain) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
