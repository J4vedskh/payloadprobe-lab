package com.javed.payloadprobe.api;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

@Component
public class XmlPayloadValidator {

    static final int DEFAULT_MAX_PAYLOAD_BYTES = 256 * 1024;

    private static final String WRAPPER_ELEMENT = "payloadprobe-validation-wrapper";
    private static final String WRAPPER_START = "<" + WRAPPER_ELEMENT + ">";
    private static final String WRAPPER_END = "</" + WRAPPER_ELEMENT + ">";

    private final int maxPayloadBytes;

    public XmlPayloadValidator(
            @Value("${payloadprobe.validation.max-payload-bytes:" + DEFAULT_MAX_PAYLOAD_BYTES + "}")
            int maxPayloadBytes) {
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("payloadprobe.validation.max-payload-bytes must be greater than zero");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    String validate(String xmlInput) {
        if (xmlInput == null || xmlInput.isBlank()) {
            throw new InvalidXmlPayloadException(InvalidXmlPayloadException.Reason.BLANK);
        }
        if (xmlInput.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new InvalidXmlPayloadException(InvalidXmlPayloadException.Reason.TOO_LARGE);
        }

        ElementCountingHandler handler = new ElementCountingHandler();
        try {
            XMLReader reader = secureXmlReader(handler);
            reader.parse(new InputSource(new StringReader(wrapForValidation(xmlInput))));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new InvalidXmlPayloadException(InvalidXmlPayloadException.Reason.MALFORMED);
        }

        if (!handler.hasPayloadElement()) {
            throw new InvalidXmlPayloadException(InvalidXmlPayloadException.Reason.MALFORMED);
        }
        return xmlInput;
    }

    private static XMLReader secureXmlReader(DefaultHandler handler)
            throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External entity resolution is disabled");
        });
        return reader;
    }

    private static String wrapForValidation(String xmlInput) throws SAXException {
        int contentStart = xmlInput.startsWith("\uFEFF") ? 1 : 0;
        if (!xmlInput.startsWith("<?xml", contentStart)) {
            return WRAPPER_START + xmlInput + WRAPPER_END;
        }

        int nameEnd = contentStart + "<?xml".length();
        if (nameEnd >= xmlInput.length() || !Character.isWhitespace(xmlInput.charAt(nameEnd))) {
            return WRAPPER_START + xmlInput + WRAPPER_END;
        }
        int declarationEnd = xmlInput.indexOf("?>", nameEnd);
        if (declarationEnd < 0) {
            throw new SAXException("Unterminated XML declaration");
        }
        return xmlInput.substring(0, declarationEnd + 2)
                + WRAPPER_START
                + xmlInput.substring(declarationEnd + 2)
                + WRAPPER_END;
    }

    private static final class ElementCountingHandler extends DefaultHandler {

        private int depth;
        private int elementCount;

        @Override
        public void startElement(String uri, String localName, String qualifiedName, Attributes attributes) {
            depth++;
            if (depth > 1) {
                elementCount++;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qualifiedName) {
            depth--;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }

        private boolean hasPayloadElement() {
            return elementCount > 0;
        }
    }
}
