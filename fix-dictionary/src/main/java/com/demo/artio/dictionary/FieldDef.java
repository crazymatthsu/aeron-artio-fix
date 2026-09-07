package com.demo.artio.dictionary;

import java.util.List;

/** A {@code <field number="..." name="..." type="..."/>} declaration from {@code <fields>}. */
public record FieldDef(int number, String name, String type, List<EnumValue> values) {

    public FieldDef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field declaration needs a name");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("field " + name + " needs a type");
        }
        values = List.copyOf(values);
    }

    public FieldDef(final int number, final String name, final String type) {
        this(number, name, type, List.of());
    }

    public boolean isEnum() {
        return !values.isEmpty();
    }

    public FieldDef withType(final String type) {
        return new FieldDef(number, name, type, values);
    }

    public FieldDef withValues(final List<EnumValue> values) {
        return new FieldDef(number, name, type, values);
    }
}
