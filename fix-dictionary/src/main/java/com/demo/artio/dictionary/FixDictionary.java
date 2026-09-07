package com.demo.artio.dictionary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A whole FIX dictionary: the root attributes plus the header, trailer, messages,
 * components and field declarations.
 *
 * <p>QuickFIX/J and Artio use the same XML schema, so one model serves both. The
 * differences are semantic, not structural, and live in {@link ArtioDictionaryConverter}.</p>
 *
 * @param specType the {@code type} attribute ({@code FIX}, {@code FIXT}, ...); null when the
 *                 input omitted it, in which case Artio defaults it to {@code FIX}
 * @param major the {@code major} attribute; Artio derives {@code BeginString} from major/minor
 * @param minor the {@code minor} attribute
 * @param servicePack the {@code servicepack} attribute, or null when absent
 */
public record FixDictionary(
        String specType,
        int major,
        int minor,
        String servicePack,
        List<DictionaryEntry> header,
        List<DictionaryEntry> trailer,
        List<MessageDef> messages,
        List<ComponentDef> components,
        List<FieldDef> fields) {

    public FixDictionary {
        header = List.copyOf(header);
        trailer = List.copyOf(trailer);
        messages = List.copyOf(messages);
        components = List.copyOf(components);
        fields = List.copyOf(fields);
    }

    /** {@code FIX.4.2} for major 4, minor 2 -- what Artio's generated dictionary reports. */
    public String beginString() {
        return (specType == null ? "FIX" : specType) + "." + major + "." + minor;
    }

    public Map<String, FieldDef> fieldsByName() {
        return index(fields, FieldDef::name);
    }

    public Map<String, ComponentDef> componentsByName() {
        return index(components, ComponentDef::name);
    }

    public Optional<MessageDef> messageByMsgType(final String msgType) {
        return messages.stream().filter(m -> m.msgType().equals(msgType)).findFirst();
    }

    private static <T> Map<String, T> index(final List<T> values, final Function<T, String> key) {
        final Map<String, T> byName = new LinkedHashMap<>();
        for (final T value : values) {
            byName.putIfAbsent(key.apply(value), value);
        }
        return byName;
    }
}
