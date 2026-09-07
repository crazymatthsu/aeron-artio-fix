package com.demo.artio.dictionary;

import java.util.List;

/**
 * One child of an aggregate ({@code <header>}, {@code <trailer>}, {@code <message>},
 * {@code <component>} or {@code <group>}) in a QuickFIX/J or Artio FIX dictionary.
 *
 * <p>Both formats use the same three element kinds, so the model is shared by the
 * reader, the converter and the writer.</p>
 */
public sealed interface DictionaryEntry permits FieldRef, ComponentRef, GroupRef {

    /** The {@code name} attribute: a field name, a component name or a group counter name. */
    String name();

    /** The {@code required} attribute; a missing attribute is read as {@code false}, as Artio does. */
    boolean required();

    /** The entries nested under this one; empty for everything but a group. */
    default List<DictionaryEntry> children() {
        return List.of();
    }

    /** A copy of this entry with {@code required} replaced. */
    DictionaryEntry withRequired(boolean required);
}
