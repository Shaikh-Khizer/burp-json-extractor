package com.jsonextractor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;

/**
 * Factory: Burp calls provideHttpResponseEditor() once per editor panel
 * (Repeater, Proxy history, etc.).
 */
public class JsonResponseEditorProvider implements HttpResponseEditorProvider {

    private final MontoyaApi api;

    public JsonResponseEditorProvider(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext context) {
        return new JsonExtractorTab(api);
    }
}
