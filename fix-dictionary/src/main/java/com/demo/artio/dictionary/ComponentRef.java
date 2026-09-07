package com.demo.artio.dictionary;

/** A {@code <component name="..." required="Y|N"/>} reference inside an aggregate. */
public record ComponentRef(String name, boolean required) implements DictionaryEntry {

    public ComponentRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("component reference needs a name");
        }
    }

    @Override
    public ComponentRef withRequired(final boolean required) {
        return new ComponentRef(name, required);
    }
}
