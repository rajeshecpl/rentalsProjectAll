package com.rentopia.auth;

import com.jayway.jsonpath.JsonPath;
import com.rentopia.support.CapturingOtpSender;
import com.rentopia.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end HTTP tests for authentication, authorization and the profile API. */
class AuthFlowIT extends IntegrationTest {

    private static final String PASSWORD = "S3cure-Passw0rd";

    @Autowired
    WebApplicationContext context;

    @Autowired
    CapturingOtpSender otpSender;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void registerLoginAndReadProfile() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Asha Rao", email.toUpperCase(), "CUSTOMER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))   // normalized to lower case
                .andExpect(jsonPath("$.user.roles").value(containsInAnyOrder("CUSTOMER")));

        String token = accessToken(login(email, PASSWORD));

        mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void vendorRegistrationGrantsVendorAndCustomerRoles() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Ravi Cars", uniqueEmail(), "VENDOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.roles").value(containsInAnyOrder("CUSTOMER", "VENDOR")));
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register(email);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Other", email.toUpperCase(), "CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameError() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (String[] creds : new String[][]{{email, "wrong-password"}, {uniqueEmail(), PASSWORD}}) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(creds[0], creds[1])))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        mvc.perform(get("/api/v1/users/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/users/profile"));

        mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotReachAdminOrVendorEndpoints() throws Exception {
        String token = accessToken(register(uniqueEmail()));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/vendor/listings").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshRotatesTokensAndReuseRevokesAllSessions() throws Exception {
        MvcResult registered = register(uniqueEmail());
        String refresh1 = JsonPath.read(registered.getResponse().getContentAsString(), "$.refreshToken");

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refresh1)))
                .andExpect(status().isOk())
                .andReturn();
        String refresh2 = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Replaying the old token looks like theft: rejected, and the new one is revoked too.
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refresh1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refresh2)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String refresh = JsonPath.read(register(uniqueEmail()).getResponse().getContentAsString(), "$.refreshToken");
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshJson(refresh)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshJson(refresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputReturnsStandardValidationError() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"","email":"not-an-email","password":"short","accountType":"CUSTOMER"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("email")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("password")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("fullName")));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void updateProfileAndVerifyPhoneWithOtp() throws Exception {
        String token = accessToken(register(uniqueEmail()));
        String phone = "+91" + (9_000_000_000L + (long) (Math.random() * 999_999_999L));

        mvc.perform(put("/api/v1/users/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Asha R","phone":"%s","dateOfBirth":"1995-04-12","gender":"FEMALE"}"""
                                .formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Asha R"))
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.phoneVerified").value(false))
                .andExpect(jsonPath("$.gender").value("FEMALE"));

        mvc.perform(post("/api/v1/auth/otp/send").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"VERIFY_PHONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationHint").value("******" + phone.substring(phone.length() - 4)));

        String code = otpSender.lastCodeFor(phone);
        String wrong = code.equals("000000") ? "111111" : "000000";

        mvc.perform(post("/api/v1/auth/verify-otp").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"VERIFY_PHONE\",\"code\":\"%s\"}".formatted(wrong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));

        mvc.perform(post("/api/v1/auth/verify-otp").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"VERIFY_PHONE\",\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.phoneVerified").value(true));

        // A used code cannot be replayed.
        mvc.perform(post("/api/v1/auth/verify-otp").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"VERIFY_PHONE\",\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otpSendingIsRateLimited() throws Exception {
        String token = accessToken(register(uniqueEmail()));
        // test profile allows 3 sends per window
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/otp/send").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"VERIFY_EMAIL\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/auth/otp/send").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"VERIFY_EMAIL\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_RATE_LIMITED"));
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult register(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Test User", email, "CUSTOMER")))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.rentopia";
    }

    private static String registerJson(String name, String email, String type) {
        return """
                {"fullName":"%s","email":"%s","password":"%s","accountType":"%s"}"""
                .formatted(name, email, PASSWORD, type);
    }

    private static String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    private static String refreshJson(String token) {
        return """
                {"refreshToken":"%s"}""".formatted(token);
    }
}
