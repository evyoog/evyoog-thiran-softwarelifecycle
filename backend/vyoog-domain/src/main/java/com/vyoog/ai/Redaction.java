package com.vyoog.ai;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.util.Map;

/**
 * VYB-0937: what {@link Redactor} did to one piece of text.
 *
 * @param text   what may be sent
 * @param tokens token to original, for putting personal data back in the reply (never holds a secret)
 * @param counts how many values of each class were replaced, for the log and metrics (never the values)
 */
public record Redaction(String text, Map<String, String> tokens, Map<DataClass, Integer> counts) {

    public boolean changedAnything() {
        return !counts.isEmpty();
    }

    /**
     * Puts the original values back where the provider's reply uses a token. A token the reply does not contain is
     * simply absent; a token the provider invented is left as written (it is harmless text).
     *
     * @param insideJson the reply is JSON text, so each value goes in as a JSON string would (a quote or backslash in
     *                   a name must not break the document the caller is about to parse)
     */
    public String restore(String reply, boolean insideJson) {
        if (reply == null || tokens.isEmpty()) return reply;
        String out = reply;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            if (!out.contains(e.getKey())) continue;
            String original = insideJson ? new String(JsonStringEncoder.getInstance().quoteAsString(e.getValue())) : e.getValue();
            out = out.replace(e.getKey(), original);
        }
        return out;
    }
}
