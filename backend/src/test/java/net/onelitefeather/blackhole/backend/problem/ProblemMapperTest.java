package net.onelitefeather.blackhole.backend.problem;

import io.micronaut.http.HttpStatus;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicError;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemMapperTest {

    private static final String INSTANCE = "/punishment/appeal";

    private record SampleError(String errorMessage) implements ErrorResponse {
    }

    private record SamplePublicError(PublicErrorKind kind, String code, String availableFrom) implements PublicError {
    }

    @Test
    void anErrorWithACodeTakesItsTypeTitleAndDetailFromTheCode() {
        ProblemMapper.Mapped mapped = ProblemMapper.fromError(
                new SampleError("The appeal window has not opened yet."), HttpStatus.CONFLICT, "NOT_ELIGIBLE", INSTANCE);

        assertEquals(HttpStatus.CONFLICT, mapped.status(), "the status is the one given");
        assertEquals(409, mapped.body().status(), "the body carries the same status");
        assertEquals(ProblemType.forCode("NOT_ELIGIBLE"), mapped.body().type(), "the type comes from the code");
        assertEquals("Not eligible", mapped.body().title(), "the title is the code, humanised");
        assertEquals("The appeal window has not opened yet.", mapped.body().detail(), "the detail is the error's message");
        assertEquals("NOT_ELIGIBLE", mapped.body().extensions().get("code"), "the code is carried as an extension");
    }

    @Test
    void extensionMembersTravelBesideTheCodeAndANullOneIsLeftOut() {
        Map<String, Object> extensions = new java.util.HashMap<>();
        extensions.put("availableFrom", 1_800_000_000_000L);
        extensions.put("backendCount", null);
        ProblemMapper.Mapped mapped = ProblemMapper.fromError(
                new SampleError("An appeal is not possible yet."), HttpStatus.CONFLICT, "NOT_YET", INSTANCE, extensions);

        assertEquals("NOT_YET", mapped.body().extensions().get("code"), "the code is still carried");
        assertEquals(1_800_000_000_000L, mapped.body().extensions().get("availableFrom"), "the extension is carried");
        assertFalse(mapped.body().extensions().containsKey("backendCount"), "a null extension is not a member");
    }

    @Test
    void anInvalidCodeKindKeepsItsNameAsTheCode() {
        ProblemMapper.Mapped mapped = ProblemMapper.fromKind(
                new SampleError("The link code is invalid or expired."), net.onelitefeather.blackhole.backend.response.ErrorKind.INVALID_CODE, INSTANCE);

        assertEquals(HttpStatus.BAD_REQUEST, mapped.status(), "the kind's status");
        assertEquals("INVALID_CODE", mapped.body().extensions().get("code"), "the link clients branch on this code");
        assertEquals(ProblemType.forCode("INVALID_CODE"), mapped.body().type(), "the type is the code's slug");
    }

    @Test
    void aPublicErrorTakesItsStatusFromTheKindAndCarriesItsAvailableFrom() {
        ProblemMapper.Mapped mapped = ProblemMapper.fromPublicError(
                new SamplePublicError(PublicErrorKind.CONFLICT, "NOT_ELIGIBLE", "2026-11-01T10:00:00Z"), INSTANCE);

        assertEquals(HttpStatus.CONFLICT, mapped.status(), "the status comes from the public error kind");
        assertEquals("NOT_ELIGIBLE", mapped.body().extensions().get("code"), "the public code is carried");
        assertEquals("2026-11-01T10:00:00Z", mapped.body().extensions().get("availableFrom"), "availableFrom is carried when present");
    }

    @Test
    void aPublicErrorWithoutAvailableFromCarriesNoAvailableFromMember() {
        ProblemMapper.Mapped mapped = ProblemMapper.fromPublicError(
                new SamplePublicError(PublicErrorKind.UNAUTHORIZED, "INVALID_BAN_ID", null), INSTANCE);

        assertFalse(mapped.body().extensions().containsKey("availableFrom"), "an absent availableFrom is not a member");
        assertTrue(mapped.body().type().endsWith("/invalid-ban-id"), "the type is the code's slug");
    }

    @Test
    void theUnexpectedErrorIsAFixedInternalError() {
        ProblemMapper.Mapped mapped = ProblemMapper.unexpected(INSTANCE);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapped.status(), "an unknown failure is a 500");
        assertEquals(Map.of(), mapped.body().extensions(), "the 500 carries no extension data");
        assertFalse(mapped.body().detail().contains("Exception"), "the detail never names an exception");
    }
}
