package com.javed.payloadprobe.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class XmlPayloadValidatorTests {

    private final XmlPayloadValidator validator =
            new XmlPayloadValidator(XmlPayloadValidator.DEFAULT_MAX_PAYLOAD_BYTES);

    @Test
    void acceptsDocumentsFragmentsAndNamespacesWithoutChangingThePayload() {
        String document = "<?xml version=\"1.0\"?><response><status>ok</status></response>";
        String fragment = "<?xml version=\"1.0\"?><MatDate>1722497647</MatDate><AmountRedeem>100</AmountRedeem>";
        String namespaced = "<S:Envelope xmlns:S=\"urn:test\"><S:Body/></S:Envelope>";

        assertThat(validator.validate(document)).isSameAs(document);
        assertThat(validator.validate(fragment)).isSameAs(fragment);
        assertThat(validator.validate(namespaced)).isSameAs(namespaced);
    }

    @Test
    void rejectsBlankOrElementFreeContent() {
        assertReason(" \n\t", InvalidXmlPayloadException.Reason.BLANK);
        assertReason("plain text", InvalidXmlPayloadException.Reason.MALFORMED);
        assertReason("<!-- comment only -->", InvalidXmlPayloadException.Reason.MALFORMED);
    }

    @Test
    void rejectsMalformedXmlAndDoctypes() {
        assertReason("<response>", InvalidXmlPayloadException.Reason.MALFORMED);
        assertReason(
                "<!DOCTYPE response [<!ENTITY probe SYSTEM \"file:///etc/passwd\">]>"
                        + "<response>&probe;</response>",
                InvalidXmlPayloadException.Reason.MALFORMED);
    }

    @Test
    void rejectsMalformedXmlDeclarations() {
        assertReason("<?xml version=\"2.0\"?><r/>", InvalidXmlPayloadException.Reason.MALFORMED);
        assertReason("<?xml encoding=\"UTF-8\"?><r/>", InvalidXmlPayloadException.Reason.MALFORMED);
        assertReason("<?xml version=\"1.0\" broken?><r/>", InvalidXmlPayloadException.Reason.MALFORMED);
        assertReason(" <?xml version=\"1.0\"?><r/>", InvalidXmlPayloadException.Reason.MALFORMED);
    }

    @Test
    void enforcesTheConfiguredUtf8ByteLimit() {
        XmlPayloadValidator smallValidator = new XmlPayloadValidator(48);
        String exactLimit = "<r>" + "a".repeat(41) + "</r>";
        String overLimit = "<r>" + "a".repeat(42) + "</r>";
        String multibyteOverLimit = "<r>" + "é".repeat(21) + "</r>";

        assertThat(exactLimit.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(48);
        assertThat(smallValidator.validate(exactLimit)).isSameAs(exactLimit);
        assertReason(smallValidator, overLimit, InvalidXmlPayloadException.Reason.TOO_LARGE);
        assertThat(multibyteOverLimit).hasSize(28);
        assertReason(smallValidator, multibyteOverLimit, InvalidXmlPayloadException.Reason.TOO_LARGE);
    }

    private void assertReason(String payload, InvalidXmlPayloadException.Reason expectedReason) {
        assertReason(validator, payload, expectedReason);
    }

    private static void assertReason(
            XmlPayloadValidator target,
            String payload,
            InvalidXmlPayloadException.Reason expectedReason) {
        assertThatThrownBy(() -> target.validate(payload))
                .isInstanceOfSatisfying(
                        InvalidXmlPayloadException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expectedReason));
    }
}
