package com.demo.artio.dictionary;

import java.util.List;

/**
 * A {@code <group name="NoXxx" required="Y|N"> ... </group>} inside an aggregate.
 *
 * <p>{@code name} is the name of the counter field (tag 384, 382, ...). Artio turns it
 * into a synthetic {@code <name>GroupCounter} field of type {@code NUMINGROUP} and calls
 * the group {@code XxxGroup}; the dictionary itself only carries the counter name.</p>
 */
public record GroupRef(String name, boolean required, List<DictionaryEntry> children)
        implements DictionaryEntry {

    public GroupRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("group needs a counter field name");
        }
        children = List.copyOf(children);
    }

    @Override
    public GroupRef withRequired(final boolean required) {
        return new GroupRef(name, required, children);
    }

    /** A copy of this group with different children. */
    public GroupRef withChildren(final List<DictionaryEntry> children) {
        return new GroupRef(name, required, children);
    }
}
