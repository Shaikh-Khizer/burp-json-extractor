package com.jsonextractor;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/**
 * Entry point. Burp calls initialize() when the extension loads.
 * Compile with: montoya-api-<version>.jar on the classpath.
 */
public class JsonExtractorExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("JSON Extractor");
        api.logging().logToOutput("JSON Extractor loaded. Look for the 'JSON' tab in Repeater/Proxy.");

        api.userInterface().registerHttpResponseEditorProvider(
            new JsonResponseEditorProvider(api)
        );
    }
}
