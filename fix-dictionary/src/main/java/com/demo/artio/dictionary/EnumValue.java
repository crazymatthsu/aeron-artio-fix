package com.demo.artio.dictionary;

/**
 * A {@code <value enum="..." description="..."/>} on a field.
 *
 * <p>{@code representation} is the on-the-wire value; {@code description} becomes the name of
 * a Java enum constant in the generated codec, so it has to be a legal Java identifier and
 * unique within its field.</p>
 */
public record EnumValue(String representation, String description) {

    public EnumValue {
        if (representation == null) {
            throw new IllegalArgumentException("enum value needs a representation");
        }
    }

    public EnumValue withDescription(final String description) {
        return new EnumValue(representation, description);
    }
}
