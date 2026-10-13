package com.codearena.service;

import com.codearena.dto.AuthResponse;
import com.codearena.dto.RegisterRequest;
import com.codearena.entity.Role;
import com.codearena.entity.User;
import com.codearena.repository.UserRepository;
import com.codearena.security.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Milestone 2 - Email notifications: verifies a welcome email is sent
 * exactly once, on successful user registration.
 */
public class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private EmailService emailService;

    @Mock
    private GoogleTokenVerifierService googleTokenVerifierService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testRegisterSendsWelcomeEmail() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Alice");
        request.setEmail("alice@example.com");
        request.setPassword("password123");

        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");

        User savedUser = new User();
        savedUser.setId(1L);
        savedUser.setName("Alice");
        savedUser.setEmail("alice@example.com");
        savedUser.setRole(Role.USER);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtUtil.generateToken("alice@example.com", 1L)).thenReturn("jwt-token");

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("jwt-token", response.getToken());
        // The welcome email must fire exactly once, to the registered address, with the user's name.
        verify(emailService, times(1)).sendWelcomeEmail("alice@example.com", "Alice");
        verify(emailService, never()).sendLoginAlertEmail(anyString(), anyString());
    }

    @Test
    public void testRegisterDuplicateEmailDoesNotSendEmail() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Bob");
        request.setEmail("bob@example.com");
        request.setPassword("password123");

        when(userRepository.existsByEmail("bob@example.com")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> authService.register(request));
        verify(emailService, never()).sendWelcomeEmail(anyString(), anyString());
    }

    @Test
    public void testLoginWithGoogleMockToken() {
        String mockToken = "mock-google-id-token:Charlie:charlie@example.com";
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("mock-google-id-123");
        payload.setEmail("charlie@example.com");
        payload.set("name", "Charlie");

        when(googleTokenVerifierService.verify(mockToken)).thenReturn(payload);
        when(userRepository.findByEmail("charlie@example.com")).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pwd");

        User savedUser = new User();
        savedUser.setId(3L);
        savedUser.setName("Charlie");
        savedUser.setEmail("charlie@example.com");
        savedUser.setRole(Role.USER);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtUtil.generateToken("charlie@example.com", 3L)).thenReturn("mock-jwt-token");

        AuthResponse response = authService.loginWithGoogle(mockToken);

        assertNotNull(response);
        assertEquals("mock-jwt-token", response.getToken());
        verify(emailService, times(1)).sendGoogleWelcomeEmail("charlie@example.com", "Charlie");
    }
}
