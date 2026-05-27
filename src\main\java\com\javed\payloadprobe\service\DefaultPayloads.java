package com.javed.payloadprobe.service;

public final class DefaultPayloads {

    public static final String DEFAULT_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response>
                <status>success</status>
                <data>
                    <response>
                        <errorCode>0</errorCode>
                        <responseMessage>Default XML response</responseMessage>
                    </response>
                </data>
            </response>
            """;

    private DefaultPayloads() {
    }

    public static String notFoundXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <response>
                    <status>failure</status>
                    <data>
                        <response>No XML response found</response>
                    </data>
                </response>
                """;
    }
}
