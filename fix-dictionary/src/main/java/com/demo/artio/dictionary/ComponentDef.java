package com.demo.artio.dictionary;

import java.util.List;

/** A {@code <component name="..."> ... </component>} declaration. */
public record ComponentDef(String name, List<DictionaryEntry> entries) {

    public ComponentDef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("component needs a name");
        }
        entries = List.copyOf(entries);
    }

    public ComponentDef withEntries(final List<DictionaryEntry> entries) {
        return new ComponentDef(name, entries);
    }
}
