package io.github.chosomeister.keycloak.storage.ldap.mappers;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A relying party matches users on the exact SID string, so these cases pin the byte order of both
 * halves of the format: the identifier authority is big endian while the sub authorities are little
 * endian, and getting either backwards produces a plausible looking but wrong SID.
 */
class ActiveDirectorySidTest {

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value.replace(" ", ""));
    }

    @Test
    void convertsARealDomainAccountSid() {
        // S-1-5-21-2127521184-1604012920-1887927527-72713
        byte[] binary = hex("01 05 00 00 00 00 00 05 15 00 00 00 A065CF7E 784B9B5F E77C8770 091C0100");

        assertEquals("S-1-5-21-2127521184-1604012920-1887927527-72713",
                ActiveDirectorySid.toStringForm(binary));
    }

    @Test
    void convertsAWellKnownSidWithNoSubAuthorities() {
        // S-1-0 : the null authority
        assertEquals("S-1-0", ActiveDirectorySid.toStringForm(hex("01 00 00 00 00 00 00 00")));
    }

    @Test
    void convertsTheBuiltinAdministratorsSid() {
        // S-1-5-32-544
        assertEquals("S-1-5-32-544",
                ActiveDirectorySid.toStringForm(hex("01 02 00 00 00 00 00 05 20000000 20020000")));
    }

    @Test
    void readsTheIdentifierAuthorityBigEndian() {
        // An authority above 2^32 can only be expressed across the upper bytes.
        assertEquals("S-1-281474976710655",
                ActiveDirectorySid.toStringForm(hex("01 00 FF FF FF FF FF FF")));
    }

    @Test
    void treatsSubAuthoritiesAsUnsigned() {
        // 0xFFFFFFFF little endian must read as 4294967295, not -1.
        assertEquals("S-1-5-4294967295",
                ActiveDirectorySid.toStringForm(hex("01 01 00 00 00 00 00 05 FFFFFFFF")));
    }

    @Test
    void decodesTheBase64FormKeycloakHandsOver() {
        byte[] binary = hex("01 05 00 00 00 00 00 05 15 00 00 00 A065CF7E 784B9B5F E77C8770 091C0100");
        String base64 = Base64.getEncoder().encodeToString(binary);

        assertEquals("S-1-5-21-2127521184-1604012920-1887927527-72713",
                ActiveDirectorySid.fromLdapValue(base64));
    }

    @Test
    void passesThroughAValueAlreadyInStringForm() {
        assertEquals("S-1-5-21-1-2-3-1001", ActiveDirectorySid.fromLdapValue("S-1-5-21-1-2-3-1001"));
        assertEquals("S-1-5-21-1-2-3-1001", ActiveDirectorySid.fromLdapValue("  S-1-5-21-1-2-3-1001  "));
    }

    @Test
    void treatsAbsentValuesAsNothingToConvert() {
        assertNull(ActiveDirectorySid.fromLdapValue(null));
        assertNull(ActiveDirectorySid.fromLdapValue("   "));
    }

    @Test
    void rejectsTruncatedAndInconsistentInput() {
        // Shorter than the fixed header.
        assertThrows(IllegalArgumentException.class, () -> ActiveDirectorySid.toStringForm(hex("01 05 00")));
        // Declares five sub authorities but carries one.
        assertThrows(IllegalArgumentException.class,
                () -> ActiveDirectorySid.toStringForm(hex("01 05 00 00 00 00 00 05 15000000")));
        // Declares more sub authorities than the format allows.
        assertThrows(IllegalArgumentException.class,
                () -> ActiveDirectorySid.toStringForm(hex("01 FF 00 00 00 00 00 05")));
        assertThrows(IllegalArgumentException.class, () -> ActiveDirectorySid.toStringForm(null));
    }
}
