package net.onelitefeather.blackhole.backend.evidence.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ByteRangeTest {

    @Test
    void noHeaderServesTheWholeFile() {
        assertInstanceOf(ByteRange.Full.class, ByteRange.parse(null, 100));
    }

    @Test
    void aClosedRangeIsServedAsGiven() {
        assertEquals(new ByteRange.Partial(0, 9), ByteRange.parse("bytes=0-9", 100));
    }

    @Test
    void anOpenEndedRangeRunsToTheLastByte() {
        assertEquals(new ByteRange.Partial(90, 99), ByteRange.parse("bytes=90-", 100));
    }

    @Test
    void aSuffixRangeIsTheLastBytes() {
        assertEquals(new ByteRange.Partial(95, 99), ByteRange.parse("bytes=-5", 100));
    }

    @Test
    void aSuffixLongerThanTheFileIsTheWholeFileAsPartial() {
        assertEquals(new ByteRange.Partial(0, 99), ByteRange.parse("bytes=-500", 100));
    }

    @Test
    void anEndBeyondTheFileIsClamped() {
        assertEquals(new ByteRange.Partial(50, 99), ByteRange.parse("bytes=50-5000", 100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=100-", "bytes=100-120", "bytes=-0"})
    void aRangeBehindTheEndIsUnsatisfiable(String header) {
        assertInstanceOf(ByteRange.Unsatisfiable.class, ByteRange.parse(header, 100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"items=0-9", "bytes=0-1,5-6", "bytes=abc", "bytes=-", "bytes=9-1", "bytes=99999999999999999999-"})
    void anythingElseIsIgnored(String header) {
        assertInstanceOf(ByteRange.Full.class, ByteRange.parse(header, 100));
    }
}
