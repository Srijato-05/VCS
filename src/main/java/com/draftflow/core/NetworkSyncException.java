package com.draftflow.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NetworkSyncException extends IOException {
    private final List<String> suggestions;

    public NetworkSyncException(String message) {
        super(message);
        this.suggestions = new ArrayList<>();
    }

    public NetworkSyncException(String message, Throwable cause) {
        super(message, cause);
        this.suggestions = new ArrayList<>();
    }

    public NetworkSyncException(String message, List<String> suggestions) {
        super(message);
        this.suggestions = suggestions != null ? new ArrayList<>(suggestions) : new ArrayList<>();
    }

    public NetworkSyncException(String message, List<String> suggestions, Throwable cause) {
        super(message, cause);
        this.suggestions = suggestions != null ? new ArrayList<>(suggestions) : new ArrayList<>();
    }

    public List<String> getSuggestions() {
        return Collections.unmodifiableList(suggestions);
    }
}
