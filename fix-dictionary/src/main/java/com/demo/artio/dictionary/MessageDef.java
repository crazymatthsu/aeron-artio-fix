package com.demo.artio.dictionary;

import java.util.List;

/**
 * A {@code <message name="..." msgtype="..." msgcat="..."> ... </message>} declaration.
 *
 * <p>{@code msgCat} may be null coming out of the reader: QuickFIX/J tolerates a missing
 * {@code msgcat} attribute, Artio's parser does not. The converter fills it in.</p>
 */
public record MessageDef(String name, String msgType, String msgCat, List<DictionaryEntry> entries) {

    public MessageDef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("message needs a name");
        }
        if (msgType == null || msgType.isBlank()) {
            throw new IllegalArgumentException("message " + name + " needs a msgtype");
        }
        entries = List.copyOf(entries);
    }

    public MessageDef withMsgCat(final String msgCat) {
        return new MessageDef(name, msgType, msgCat, entries);
    }

    public MessageDef withEntries(final List<DictionaryEntry> entries) {
        return new MessageDef(name, msgType, msgCat, entries);
    }
}
