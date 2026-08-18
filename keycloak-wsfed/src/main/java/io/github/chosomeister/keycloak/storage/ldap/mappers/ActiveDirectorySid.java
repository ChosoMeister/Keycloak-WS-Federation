package io.github.chosomeister.keycloak.storage.ldap.mappers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Converts the binary {@code objectSid} Active Directory publishes over LDAP into the SDDL string
 * form that AD FS issues as the {@code primarysid} claim.
 *
 * <p>The binary layout is defined by [MS-DTYP] section 2.4.2.2:
 *
 * <pre>
 *   offset 0      revision, one byte
 *   offset 1      sub-authority count, one byte
 *   offset 2..7   identifier authority, six bytes, big endian
 *   offset 8..    that many sub-authorities, four bytes each, little endian
 * </pre>
 *
 * which renders as {@code S-<revision>-<authority>-<sub authority>...}. Note the differing byte
 * order between the authority and the sub-authorities; it is a property of the format, not a
 * mistake. Relying parties match users on the exact string, so a malformed SID is worse than an
 * absent one and every input that does not describe a whole SID is rejected.
 */
public final class ActiveDirectorySid {

    private static final int HEADER_BYTES = 8;
    private static final int SUB_AUTHORITY_BYTES = 4;
    private static final int MAX_SUB_AUTHORITIES = 15;

    private ActiveDirectorySid() {
    }

    /**
     * @param binary the raw value of the objectSid attribute
     * @return the SID in SDDL string form, for example {@code S-1-5-21-1-2-3-1001}
     * @throws IllegalArgumentException when the input does not describe a complete SID
     */
    public static String toStringForm(byte[] binary) {
        if (binary == null || binary.length < HEADER_BYTES) {
            throw new IllegalArgumentException("An objectSid is at least " + HEADER_BYTES
                    + " bytes, got " + (binary == null ? "null" : binary.length));
        }

        int revision = Byte.toUnsignedInt(binary[0]);
        int subAuthorityCount = Byte.toUnsignedInt(binary[1]);

        if (subAuthorityCount > MAX_SUB_AUTHORITIES) {
            throw new IllegalArgumentException("An objectSid holds at most " + MAX_SUB_AUTHORITIES
                    + " sub authorities, got " + subAuthorityCount);
        }

        int expectedLength = HEADER_BYTES + (subAuthorityCount * SUB_AUTHORITY_BYTES);
        if (binary.length != expectedLength) {
            throw new IllegalArgumentException("An objectSid declaring " + subAuthorityCount
                    + " sub authorities is " + expectedLength + " bytes, got " + binary.length);
        }

        // Six bytes, most significant first.
        long identifierAuthority = 0;
        for (int i = 2; i < HEADER_BYTES; i++) {
            identifierAuthority = (identifierAuthority << 8) | Byte.toUnsignedLong(binary[i]);
        }

        StringBuilder sid = new StringBuilder(64)
                .append("S-").append(revision).append('-').append(identifierAuthority);

        // Four bytes each, least significant first, and unsigned.
        for (int i = 0; i < subAuthorityCount; i++) {
            int offset = HEADER_BYTES + (i * SUB_AUTHORITY_BYTES);
            long subAuthority = 0;
            for (int b = SUB_AUTHORITY_BYTES - 1; b >= 0; b--) {
                subAuthority = (subAuthority << 8) | Byte.toUnsignedLong(binary[offset + b]);
            }
            sid.append('-').append(subAuthority);
        }

        return sid.toString();
    }

    /**
     * Converts a value as it arrives from Keycloak's LDAP layer, which hands binary attributes over
     * base64 encoded. A value that is already in string form is returned unchanged, so that a
     * directory exposing a pre-converted SID keeps working.
     *
     * @param value the attribute value read from the directory
     * @return the SID in SDDL string form, or null when there is nothing to convert
     */
    public static String fromLdapValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.regionMatches(true, 0, "S-", 0, 2)) {
            return trimmed;
        }

        byte[] binary;
        try {
            binary = Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            // Not base64, so treat the characters themselves as the raw bytes.
            binary = trimmed.getBytes(StandardCharsets.ISO_8859_1);
        }

        return toStringForm(binary);
    }
}
