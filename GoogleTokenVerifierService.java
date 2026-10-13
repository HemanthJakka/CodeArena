package com.codearena.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.util.Collections;

@Service
public class GoogleTokenVerifierService {

    @Value("${google.client.id:}")
    private String googleClientId;

    @Value("${google.allow-mock:false}")
    private boolean allowMock;

    /**
     * Verifies a Google ID token (credential) sent from the frontend
     * (Google Identity Services "Sign in with Google" button).
     * Returns the verified payload, or throws IllegalArgumentException if invalid.
     */
    public GoogleIdToken.Payload verify(String idTokenString) {
        if (allowMock && idTokenString != null && idTokenString.startsWith("mock-google-id-token")) {
            GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
            String name = "Mock User";
            String email = "mockuser@gmail.com";
            String googleId = "mock-google-id-12345";

            String[] parts = idTokenString.split(":", 3);
            if (parts.length >= 3) {
                name = parts[1];
                email = parts[2];
                googleId = "mock-google-id-" + Math.abs(email.hashCode());
            }

            payload.setSubject(googleId);
            payload.setEmail(email);
            payload.set("name", name);
            payload.setEmailVerified(true);
            return payload;
        }

        if (googleClientId == null || googleClientId.isBlank()) {
            throw new IllegalStateException("google.client.id is not configured on the server");
        }
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new IllegalArgumentException("Invalid Google ID token");
            }
            return idToken.getPayload();
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Could not verify Google ID token: " + ex.getMessage());
        }
    }
}
