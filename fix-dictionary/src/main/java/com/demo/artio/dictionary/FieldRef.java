package com.demo.artio.dictionary;

/** A {@code <field name="..." required="Y|N"/>} reference inside an aggregate. */
public record FieldRef(String name, boolean required) implements DictionaryEntry {

    public FieldRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field reference needs a name");
        }
    }

    @Override
    public FieldRef withRequired(final boolean required) {
        return new FieldRef(name, required);
    }
}
