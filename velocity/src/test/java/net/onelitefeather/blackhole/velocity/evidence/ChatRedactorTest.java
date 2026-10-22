package net.onelitefeather.blackhole.velocity.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatRedactorTest {

    @Test
    void anIpv4AddressIsReplaced() {
        assertEquals("my ip is [ip] ok", ChatRedactor.redact("my ip is 203.0.113.5 ok"));
    }

    @Test
    void anIpv4AddressWithAPortIsReplacedWhole() {
        assertEquals("join [ip]", ChatRedactor.redact("join 203.0.113.5:25565"));
    }

    @Test
    void aFullIpv6AddressIsReplaced() {
        assertEquals("[ip]", ChatRedactor.redact("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
    }

    @Test
    void aCompressedIpv6AddressIsReplaced() {
        assertEquals("it is [ip] now", ChatRedactor.redact("it is 2001:db8::ff00:42 now"));
    }

    @Test
    void aClockTimeIsNotTakenForAnIpv6Address() {
        assertEquals("see you at 12:30:45", ChatRedactor.redact("see you at 12:30:45"));
    }

    @Test
    void anHttpsUrlIsReplaced() {
        assertEquals("look [url] lol", ChatRedactor.redact("look https://evil.example/path?q=1 lol"));
    }

    @Test
    void aWwwAddressWithoutSchemeIsReplaced() {
        assertEquals("[url]", ChatRedactor.redact("www.example.org/x"));
    }

    @Test
    void aBareDomainWithACommonTopLevelDomainIsReplaced() {
        assertEquals("join [url] now", ChatRedactor.redact("join play.example.net now"));
    }

    @Test
    void anIpAddressInsideAUrlIsReplacedWithTheUrl() {
        assertEquals("[url]", ChatRedactor.redact("http://203.0.113.5:8080/admin"));
    }

    @Test
    void aUuidIsReplaced() {
        assertEquals("id [uuid]", ChatRedactor.redact("id 123e4567-e89b-12d3-a456-426614174000"));
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        assertEquals("gg, that was fun. see you tomorrow!", ChatRedactor.redact("gg, that was fun. see you tomorrow!"));
    }
}
