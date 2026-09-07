package com.demo.artio.dictionary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a QuickFIX/J dictionary into one Artio's {@code DictionaryParser} and
 * {@code CodecGenerationTool} accept, recording a {@link ConversionNote} for every change.
 *
 * <p>Every rule below exists because Artio fails without it. The failure each rule prevents was
 * produced by feeding Artio 0.168 a dictionary that breaks the rule and running
 * {@code DictionaryParser.parse}, then {@code CodecGenerationTool}, then {@code javac} on the
 * generated sources. The exact messages are quoted in the {@code WHY_*} constants and repeated,
 * with the reproduction, in {@code docs/02-quickfixj-to-artio-dictionary.md}. The unit tests in
 * {@code ArtioDictionaryCheckTest} re-run those reproductions against the real parser so the
 * justifications cannot go stale.</p>
 *
 * <p>Conversion is a fixpoint: converting an already-converted dictionary produces no notes.</p>
 */
public final class ArtioDictionaryConverter {

    // --- rule names, also used as section anchors in docs/02 ---------------------------------
    public static final String RULE_FIELD_TYPE_ALIAS = "field-type-alias";
    public static final String RULE_UNKNOWN_FIELD_TYPE = "unknown-field-type";
    public static final String RULE_DUPLICATE_FIELD_DECLARATION = "duplicate-field-declaration";
    public static final String RULE_ENUM_DESCRIPTION_REQUIRED = "enum-description-required";
    public static final String RULE_ENUM_DESCRIPTION_JAVA_NAME = "enum-description-java-name";
    public static final String RULE_ENUM_DESCRIPTION_JAVA_KEYWORD = "enum-description-java-keyword";
    public static final String RULE_ENUM_DESCRIPTION_DEDUP = "enum-description-dedup";
    public static final String RULE_ENUM_REPRESENTATION_DEDUP = "enum-representation-dedup";
    public static final String RULE_MESSAGE_CATEGORY = "message-category";
    public static final String RULE_UNDEFINED_REFERENCE = "undefined-reference";
    public static final String RULE_DUPLICATE_FIELD_IN_MESSAGE = "duplicate-field-in-message";
    public static final String RULE_SESSION_HEADER_FIELD = "session-header-field";
    public static final String RULE_SESSION_MESSAGE_FIELD = "session-message-field";
    public static final String RULE_SESSION_MESSAGE_MISSING = "session-message-missing";
    public static final String RULE_DATA_FIELD_LENGTH = "data-field-length";

    // --- the Artio failure each rule prevents, verbatim ---------------------------------------
    private static final String WHY_TYPE =
            "Field.Type.lookup ends in valueOf(), so an unknown type throws "
                    + "IllegalArgumentException: No enum constant "
                    + "uk.co.real_logic.artio.dictionary.ir.Field.Type.<name> during parsing";
    private static final String WHY_DUPLICATE_FIELD_DECLARATION =
            "DictionaryParser.parseFields throws IllegalStateException: Cannot have the same field "
                    + "name defined twice; this is against the FIX spec";
    private static final String WHY_ENUM_DESCRIPTION_REQUIRED =
            "DictionaryParser.extractEnumValues throws NullPointerException: Empty item for: "
                    + "description, and an empty description throws StringIndexOutOfBoundsException: "
                    + "Index 0 out of bounds for length 0 in enumDescriptionToJavaName";
    private static final String WHY_ENUM_JAVA_NAME =
            "the description becomes a Java enum constant, so it must be an identifier; Artio "
                    + "rewrites it with enumDescriptionToJavaName, which can silently collide with "
                    + "another value (see " + RULE_ENUM_DESCRIPTION_DEDUP + ")";
    private static final String WHY_ENUM_KEYWORD =
            "a description that is a Java reserved word generates 'enum constant expected here' "
                    + "when javac compiles the generated enum";
    private static final String WHY_ENUM_DEDUP =
            "two values with the same Java name generate 'variable <NAME> is already defined in "
                    + "enum <Field>'";
    private static final String WHY_ENUM_REPRESENTATION_DEDUP =
            "two values with the same enum representation generate 'duplicate case label' in the "
                    + "generated decode() switch";
    private static final String WHY_MESSAGE_CATEGORY =
            "DictionaryParser.parseMessages throws NullPointerException: Empty item for: msgcat";
    private static final String WHY_UNDEFINED_REFERENCE =
            "DictionaryParser throws NullPointerException (Verify.notNull: 'element for <name> must "
                    + "not be null', 'element:<name> must not be null', or Group.of on a null field) "
                    + "for a name that is referenced but never declared";
    private static final String WHY_DUPLICATE_FIELD_IN_MESSAGE =
            "DictionaryParser throws IllegalStateException: Cannot have the same field defined more "
                    + "than once on a message; this is against the FIX spec";
    private static final String WHY_SESSION_HEADER_FIELD =
            "the generated HeaderEncoder/HeaderDecoder must implement SessionHeaderEncoder and "
                    + "SessionHeaderDecoder; a missing field gives 'HeaderEncoder is not abstract and "
                    + "does not override abstract method ...' when javac compiles the codecs";
    private static final String WHY_SESSION_MESSAGE_FIELD =
            "the generated admin codec must implement Artio's Abstract<Message>Encoder/Decoder; a "
                    + "missing field gives '<Message>Encoder is not abstract and does not override "
                    + "abstract method ...' when javac compiles the codecs";
    private static final String WHY_SESSION_MESSAGE_MISSING =
            "FixDictionaryImpl.make<Message>Encoder() returns null for an admin message the "
                    + "dictionary does not declare, which the session layer dereferences at logon";
    private static final String WHY_DATA_FIELD_LENGTH =
            "DictionaryParser.checkAssociatedLengthField throws IllegalStateException: Each DATA "
                    + "field must have a corresponding LENGTH field using the suffix 'Len' or "
                    + "'Length'";

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "_", "true", "false", "null");

    public ConversionResult convert(final FixDictionary input) {
        final List<ConversionNote> notes = new ArrayList<>();

        // 1. Field declarations first: everything else resolves names against them.
        final Map<String, FieldDef> fields = convertFields(input.fields(), notes);

        final Set<String> declaredComponents = new LinkedHashSet<>();
        for (final ComponentDef component : input.components()) {
            declaredComponents.add(component.name());
        }

        // 2. Components: drop dangling references, then walk each one as Artio walks a message so
        //    a component is internally free of duplicates before any message reaches it, then
        //    pair its DATA fields. Components go first because inserting a length field into a
        //    component can create a duplicate in a message that carries the same field directly,
        //    and the message walk below is what repairs that.
        final Map<String, ComponentDef> componentsByName = new LinkedHashMap<>();
        for (final ComponentDef component : input.components()) {
            componentsByName.put(component.name(), component.withEntries(sanitiseEntries(
                    component.entries(), fields, declaredComponents, "component " + component.name(), notes)));
        }
        for (final String name : List.copyOf(componentsByName.keySet())) {
            final String owner = "component " + name;
            List<DictionaryEntry> entries = new DuplicateWalk(componentsByName, fields, notes, owner)
                    .walk(componentsByName.get(name).entries());
            entries = fixDataFields(entries, fields, owner, notes,
                    reachableTags(entries, componentsByName, fields));
            componentsByName.put(name, componentsByName.get(name).withEntries(entries));
        }

        // 3. Header and trailer. Artio never walks them for duplicates, but a field the header
        //    carries twice generates two accessors in HeaderEncoder, so the same walk applies.
        List<DictionaryEntry> header = sanitiseEntries(
                input.header(), fields, declaredComponents, "header", notes);
        header = addSessionHeaderFields(header, componentsByName, fields, notes);
        header = new DuplicateWalk(componentsByName, fields, notes, "header").walk(header);
        header = fixDataFields(header, fields, "header", notes,
                reachableTags(header, componentsByName, fields));

        List<DictionaryEntry> trailer = sanitiseEntries(
                input.trailer(), fields, declaredComponents, "trailer", notes);
        trailer = new DuplicateWalk(componentsByName, fields, notes, "trailer").walk(trailer);
        trailer = fixDataFields(trailer, fields, "trailer", notes,
                reachableTags(trailer, componentsByName, fields));

        // 4. Messages: category, dangling references, the session contract.
        final List<MessageDef> messages = new ArrayList<>();
        for (final MessageDef message : input.messages()) {
            MessageDef converted = message;
            if (converted.msgCat() == null || converted.msgCat().isBlank()) {
                final String category =
                        SessionContract.ADMIN_MSG_TYPES.contains(converted.msgType()) ? "admin" : "app";
                notes.add(ConversionNote.added(
                        RULE_MESSAGE_CATEGORY, describe(converted), "msgcat=\"" + category + "\"",
                        WHY_MESSAGE_CATEGORY));
                converted = converted.withMsgCat(category);
            }
            List<DictionaryEntry> entries = sanitiseEntries(
                    converted.entries(), fields, declaredComponents, describe(converted), notes);
            entries = addSessionMessageFields(converted, entries, componentsByName, fields, notes);
            messages.add(converted.withEntries(entries));
        }

        // 5. Admin messages Artio drives itself but the dictionary never declared.
        addMissingSessionMessages(messages, fields, notes);

        // 6. Per message, exactly Artio's duplicate walk, then DATA fields need their length
        //    partner beside them -- unless the partner is already reached elsewhere in the same
        //    message, where a second copy would be the duplicate the walk just removed.
        final List<MessageDef> fixedMessages = new ArrayList<>();
        for (final MessageDef message : messages) {
            final String owner = describe(message);
            List<DictionaryEntry> entries = new DuplicateWalk(componentsByName, fields, notes, owner)
                    .walk(message.entries());
            entries = fixDataFields(entries, fields, owner, notes,
                    reachableTags(entries, componentsByName, fields));
            fixedMessages.add(message.withEntries(entries));
        }

        final FixDictionary converted = new FixDictionary(
                input.specType(), input.major(), input.minor(), input.servicePack(),
                header, trailer, fixedMessages, List.copyOf(componentsByName.values()),
                List.copyOf(fields.values()));
        return new ConversionResult(converted, notes);
    }

    // -----------------------------------------------------------------------------------------
    // Field declarations
    // -----------------------------------------------------------------------------------------

    private Map<String, FieldDef> convertFields(
            final List<FieldDef> input, final List<ConversionNote> notes) {
        final Map<String, FieldDef> fields = new LinkedHashMap<>();
        for (final FieldDef declared : input) {
            if (fields.containsKey(declared.name())) {
                notes.add(ConversionNote.removed(
                        RULE_DUPLICATE_FIELD_DECLARATION,
                        "field " + declared.name(),
                        "second declaration with number " + declared.number(),
                        WHY_DUPLICATE_FIELD_DECLARATION));
                continue;
            }
            fields.put(declared.name(), convertField(declared, notes));
        }
        return fields;
    }

    private FieldDef convertField(final FieldDef input, final List<ConversionNote> notes) {
        FieldDef field = input;

        final String canonical = ArtioFieldTypes.canonicalise(field.type());
        if (canonical == null) {
            notes.add(new ConversionNote(
                    RULE_UNKNOWN_FIELD_TYPE, describe(field), field.type(),
                    ArtioFieldTypes.FALLBACK, WHY_TYPE));
            field = field.withType(ArtioFieldTypes.FALLBACK);
        } else if (!canonical.equals(field.type())) {
            notes.add(new ConversionNote(
                    RULE_FIELD_TYPE_ALIAS, describe(field), field.type(), canonical, WHY_TYPE));
            field = field.withType(canonical);
        }

        if (!field.values().isEmpty()) {
            field = field.withValues(convertEnumValues(field, notes));
        }
        return field;
    }

    /**
     * Two passes. The first drops repeated representations and rewrites every description into
     * the Java name Artio would give it. The second makes those names unique, and a name it has
     * to invent may not be one that any <em>other</em> value already has after the rewrite:
     * otherwise {@code ONE, ONE, ONE_2} would rename the third value, which was unique in the
     * source, to make room for the second.
     */
    private List<EnumValue> convertEnumValues(final FieldDef field, final List<ConversionNote> notes) {
        final List<EnumValue> rewritten = new ArrayList<>();
        final Set<String> representations = new HashSet<>();

        for (final EnumValue value : field.values()) {
            if (!representations.add(value.representation())) {
                notes.add(ConversionNote.removed(
                        RULE_ENUM_REPRESENTATION_DEDUP,
                        describe(field) + " value enum=\"" + value.representation() + "\"",
                        String.valueOf(value.description()),
                        WHY_ENUM_REPRESENTATION_DEDUP));
                continue;
            }

            String description = value.description();
            if (description == null || description.isBlank()) {
                description = "VALUE_" + value.representation();
                notes.add(ConversionNote.added(
                        RULE_ENUM_DESCRIPTION_REQUIRED,
                        describe(field) + " value enum=\"" + value.representation() + "\"",
                        description, WHY_ENUM_DESCRIPTION_REQUIRED));
            }

            final String identifier = toJavaName(description);
            if (!identifier.equals(description)) {
                notes.add(new ConversionNote(
                        RULE_ENUM_DESCRIPTION_JAVA_NAME,
                        describe(field) + " value enum=\"" + value.representation() + "\"",
                        description, identifier, WHY_ENUM_JAVA_NAME));
                description = identifier;
            }

            if (JAVA_KEYWORDS.contains(description)) {
                final String escaped = description + "_";
                notes.add(new ConversionNote(
                        RULE_ENUM_DESCRIPTION_JAVA_KEYWORD,
                        describe(field) + " value enum=\"" + value.representation() + "\"",
                        description, escaped, WHY_ENUM_KEYWORD));
                description = escaped;
            }

            rewritten.add(value.withDescription(description));
        }

        final Set<String> reserved = new HashSet<>();
        for (final EnumValue value : rewritten) {
            reserved.add(value.description());
        }

        final List<EnumValue> result = new ArrayList<>();
        final Set<String> assigned = new HashSet<>();
        for (final EnumValue value : rewritten) {
            String description = value.description();
            if (!assigned.add(description)) {
                final String unique = uniquify(description, reserved, assigned);
                notes.add(new ConversionNote(
                        RULE_ENUM_DESCRIPTION_DEDUP,
                        describe(field) + " value enum=\"" + value.representation() + "\"",
                        description, unique, WHY_ENUM_DEDUP));
                assigned.add(unique);
                description = unique;
            }
            result.add(value.withDescription(description));
        }
        return result;
    }

    private static String uniquify(final String base, final Set<String> reserved, final Set<String> assigned) {
        int suffix = 2;
        String candidate = base + "_" + suffix;
        while (reserved.contains(candidate) || assigned.contains(candidate)) {
            suffix++;
            candidate = base + "_" + suffix;
        }
        return candidate;
    }

    /**
     * The same transformation Artio applies in {@code DictionaryParser.enumDescriptionToJavaName};
     * doing it here makes the collisions it can create visible and repairable.
     */
    static String toJavaName(final String description) {
        final StringBuilder name = new StringBuilder(description.length() + 1);
        final char first = description.charAt(0);
        if (Character.isJavaIdentifierStart(first)) {
            name.append(first);
        } else if (Character.isJavaIdentifierPart(first)) {
            name.append('_').append(first);
        } else {
            name.append('_');
        }
        for (int i = 1; i < description.length(); i++) {
            final char next = description.charAt(i);
            name.append(Character.isJavaIdentifierPart(next) ? next : '_');
        }
        return name.toString();
    }

    // -----------------------------------------------------------------------------------------
    // Entries
    // -----------------------------------------------------------------------------------------

    /** Drops references to names the dictionary never declares; Artio dereferences null for them. */
    private List<DictionaryEntry> sanitiseEntries(
            final List<DictionaryEntry> entries,
            final Map<String, FieldDef> fields,
            final Set<String> componentNames,
            final String owner,
            final List<ConversionNote> notes) {

        final List<DictionaryEntry> result = new ArrayList<>();
        for (final DictionaryEntry entry : entries) {
            switch (entry) {
                case FieldRef field -> {
                    if (!fields.containsKey(field.name())) {
                        notes.add(ConversionNote.removed(
                                RULE_UNDEFINED_REFERENCE, owner,
                                "<field name=\"" + field.name() + "\"/>", WHY_UNDEFINED_REFERENCE));
                    } else {
                        result.add(field);
                    }
                }
                case ComponentRef component -> {
                    if (!componentNames.contains(component.name())) {
                        notes.add(ConversionNote.removed(
                                RULE_UNDEFINED_REFERENCE, owner,
                                "<component name=\"" + component.name() + "\"/>",
                                WHY_UNDEFINED_REFERENCE));
                    } else {
                        result.add(component);
                    }
                }
                case GroupRef group -> {
                    if (!fields.containsKey(group.name())) {
                        notes.add(ConversionNote.removed(
                                RULE_UNDEFINED_REFERENCE, owner,
                                "<group name=\"" + group.name() + "\"> (no such counter field)",
                                WHY_UNDEFINED_REFERENCE));
                    } else {
                        result.add(group.withChildren(sanitiseEntries(
                                group.children(), fields, componentNames,
                                owner + " group " + group.name(), notes)));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Artio's {@code DictionaryParser.identifyDuplicateFieldDefinitionsForMessage}, walked the
     * same way so the converter finds exactly what Artio finds: the entries of one root aggregate
     * in order, one set of tag numbers for the whole root, recursing into groups and into
     * components (and the components inside those). A group contributes its members but not its
     * counter, because {@code Group.of} keeps the synthetic counter field on {@code Group.numberField}
     * rather than in {@code entries()}, so Artio never adds it to the set.
     *
     * <p>What goes when a tag is reached twice:</p>
     * <ul>
     *   <li>the later copy, when the root itself owns it (a field of the message, or of a group
     *       inside the message) -- Artio reports the later one, and a group is not shared;</li>
     *   <li>the root's own copy, when the later one lives in a component -- a component is
     *       shared with other messages;</li>
     *   <li>the later copy from its component definition, when both live in components --
     *       nothing else can make every message that carries both components valid. Every other
     *       message sees the pruned component from then on, so the note names the message that
     *       caused it;</li>
     *   <li>a whole component reference reached a second time, since each of its fields would
     *       otherwise be a duplicate.</li>
     * </ul>
     */
    private final class DuplicateWalk {

        /** Where a tag was reached: the entry, whether the root owns it, and the path Artio would print. */
        private record Occurrence(DictionaryEntry entry, boolean direct, List<String> path, String component) {
        }

        private final Map<String, ComponentDef> components;
        private final Map<String, FieldDef> fields;
        private final List<ConversionNote> notes;
        private final String owner;

        private final Map<Integer, Occurrence> reached = new HashMap<>();
        private final Set<String> componentsReached = new HashSet<>();
        private final Deque<String> path = new ArrayDeque<>();
        private final Deque<String> componentStack = new ArrayDeque<>();
        /** By identity: the same field reference value can legitimately sit in two different groups. */
        private final Set<DictionaryEntry> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<String> prunedComponents = new LinkedHashSet<>();

        DuplicateWalk(
                final Map<String, ComponentDef> components,
                final Map<String, FieldDef> fields,
                final List<ConversionNote> notes,
                final String owner) {
            this.components = components;
            this.fields = fields;
            this.notes = notes;
            this.owner = owner;
        }

        /** Walks {@code rootEntries}, prunes any component definition that lost a field, and returns the pruned root. */
        List<DictionaryEntry> walk(final List<DictionaryEntry> rootEntries) {
            visit(rootEntries);
            for (final String name : prunedComponents) {
                final ComponentDef component = components.get(name);
                components.put(name, component.withEntries(prune(component.entries())));
            }
            return prune(rootEntries);
        }

        private void visit(final List<DictionaryEntry> entries) {
            for (final DictionaryEntry entry : entries) {
                switch (entry) {
                    case FieldRef field -> visitField(field);
                    case GroupRef group -> {
                        path.addLast(group.name());
                        visit(group.children());
                        path.removeLast();
                    }
                    case ComponentRef reference -> visitComponent(reference);
                }
            }
        }

        private void visitField(final FieldRef field) {
            final FieldDef declared = fields.get(field.name());
            if (declared == null) {
                return;
            }
            final Occurrence here = new Occurrence(
                    field, componentStack.isEmpty(), List.copyOf(path), componentStack.peekLast());
            final Occurrence first = reached.putIfAbsent(declared.number(), here);
            if (first == null) {
                return;
            }
            final String tag = "tag " + declared.number();
            if (here.direct()) {
                drop(here);
                note(owner + location(here), field,
                        tag + " already reached" + through(first));
            } else if (first.direct()) {
                drop(first);
                reached.put(declared.number(), here);
                note(owner + location(first), field,
                        tag + " is also reached through component path " + here.path()
                                + ", and a component is shared with other messages");
            } else {
                drop(here);
                note("component " + here.component(), field,
                        tag + " is reached twice by " + owner + ": through component path "
                                + first.path() + " and through component path " + here.path());
            }
        }

        private void visitComponent(final ComponentRef reference) {
            final ComponentDef component = components.get(reference.name());
            if (component == null) {
                return;
            }
            if (!componentsReached.add(reference.name())) {
                final Occurrence here = new Occurrence(
                        reference, componentStack.isEmpty(), List.copyOf(path), componentStack.peekLast());
                drop(here);
                notes.add(ConversionNote.removed(
                        RULE_DUPLICATE_FIELD_IN_MESSAGE,
                        here.direct() ? owner + location(here) : "component " + here.component(),
                        "<component name=\"" + reference.name() + "\"/> (reached a second time by "
                                + owner + " through path " + here.path()
                                + "; every field in it would be a duplicate)",
                        WHY_DUPLICATE_FIELD_IN_MESSAGE));
                return;
            }
            path.addLast(reference.name());
            componentStack.addLast(reference.name());
            visit(component.entries());
            componentStack.removeLast();
            path.removeLast();
        }

        private void drop(final Occurrence occurrence) {
            dropped.add(occurrence.entry());
            if (occurrence.component() != null) {
                prunedComponents.add(occurrence.component());
            }
        }

        private void note(final String element, final FieldRef field, final String detail) {
            notes.add(ConversionNote.removed(
                    RULE_DUPLICATE_FIELD_IN_MESSAGE, element,
                    "<field name=\"" + field.name() + "\"/> (" + detail + ")",
                    WHY_DUPLICATE_FIELD_IN_MESSAGE));
        }

        /** {@code " group NoLegs group NoLegAllocs"} for a field the root reaches through groups only. */
        private static String location(final Occurrence occurrence) {
            final StringBuilder text = new StringBuilder();
            for (final String group : occurrence.path()) {
                text.append(" group ").append(group);
            }
            return text.toString();
        }

        private static String through(final Occurrence first) {
            return first.path().isEmpty() ? " directly" : " through path " + first.path();
        }

        private List<DictionaryEntry> prune(final List<DictionaryEntry> entries) {
            final List<DictionaryEntry> result = new ArrayList<>(entries.size());
            for (final DictionaryEntry entry : entries) {
                if (dropped.contains(entry)) {
                    continue;
                }
                result.add(entry instanceof GroupRef group ? group.withChildren(prune(group.children())) : entry);
            }
            return result;
        }
    }

    /** Every tag number a root reaches, through groups and components, on Artio's walk. */
    private static Set<Integer> reachableTags(
            final List<DictionaryEntry> entries,
            final Map<String, ComponentDef> components,
            final Map<String, FieldDef> fields) {

        final Set<Integer> tags = new HashSet<>();
        collectTags(entries, components, fields, tags, new HashSet<>());
        return tags;
    }

    private static void collectTags(
            final List<DictionaryEntry> entries,
            final Map<String, ComponentDef> components,
            final Map<String, FieldDef> fields,
            final Set<Integer> into,
            final Set<String> visiting) {

        for (final DictionaryEntry entry : entries) {
            switch (entry) {
                case FieldRef field -> {
                    final FieldDef declared = fields.get(field.name());
                    if (declared != null) {
                        into.add(declared.number());
                    }
                }
                case GroupRef group -> collectTags(group.children(), components, fields, into, visiting);
                case ComponentRef reference -> {
                    final ComponentDef component = components.get(reference.name());
                    if (component != null && visiting.add(component.name())) {
                        collectTags(component.entries(), components, fields, into, visiting);
                        visiting.remove(component.name());
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Session contract
    // -----------------------------------------------------------------------------------------

    private List<DictionaryEntry> addSessionHeaderFields(
            final List<DictionaryEntry> header,
            final Map<String, ComponentDef> components,
            final Map<String, FieldDef> fields,
            final List<ConversionNote> notes) {

        final List<DictionaryEntry> result = new ArrayList<>(header);
        final Set<String> present = new HashSet<>();
        collectFieldNames(header, components, present, new HashSet<>());
        for (final String name : SessionContract.HEADER_FIELDS) {
            if (present.contains(name)) {
                continue;
            }
            declareIfMissing(name, fields, notes, RULE_SESSION_HEADER_FIELD, WHY_SESSION_HEADER_FIELD);
            result.add(new FieldRef(name, false));
            notes.add(ConversionNote.added(
                    RULE_SESSION_HEADER_FIELD, "header",
                    "<field name=\"" + name + "\" required=\"N\"/>", WHY_SESSION_HEADER_FIELD));
        }
        return result;
    }

    private List<DictionaryEntry> addSessionMessageFields(
            final MessageDef message,
            final List<DictionaryEntry> entries,
            final Map<String, ComponentDef> components,
            final Map<String, FieldDef> fields,
            final List<ConversionNote> notes) {

        final List<String> required = SessionContract.messageFields(message.msgType());
        if (required.isEmpty()) {
            return entries;
        }
        final Set<String> present = new HashSet<>();
        collectFieldNames(entries, components, present, new HashSet<>());

        final List<DictionaryEntry> result = new ArrayList<>(entries);
        for (final String name : required) {
            if (present.contains(name)) {
                continue;
            }
            declareIfMissing(name, fields, notes, RULE_SESSION_MESSAGE_FIELD, WHY_SESSION_MESSAGE_FIELD);
            result.add(new FieldRef(name, false));
            notes.add(ConversionNote.added(
                    RULE_SESSION_MESSAGE_FIELD, describe(message),
                    "<field name=\"" + name + "\" required=\"N\"/>", WHY_SESSION_MESSAGE_FIELD));
        }
        return result;
    }

    private void addMissingSessionMessages(
            final List<MessageDef> messages,
            final Map<String, FieldDef> fields,
            final List<ConversionNote> notes) {

        final Set<String> declared = new HashSet<>();
        for (final MessageDef message : messages) {
            declared.add(message.msgType());
        }
        for (final String msgType : List.of("0", "1", "2", "3", "4", "5", "A")) {
            if (declared.contains(msgType)) {
                continue;
            }
            final String name = SessionContract.messageName(msgType);
            final List<DictionaryEntry> entries = new ArrayList<>();
            for (final String field : SessionContract.messageFields(msgType)) {
                declareIfMissing(field, fields, notes,
                        RULE_SESSION_MESSAGE_MISSING, WHY_SESSION_MESSAGE_MISSING);
                entries.add(new FieldRef(field, SessionContract.isRequiredInAdminMessage(field)));
            }
            messages.add(new MessageDef(name, msgType, "admin", entries));
            notes.add(ConversionNote.added(
                    RULE_SESSION_MESSAGE_MISSING, "messages",
                    "<message name=\"" + name + "\" msgtype=\"" + msgType + "\" msgcat=\"admin\"/>",
                    WHY_SESSION_MESSAGE_MISSING));
            addMsgTypeEnumValue(msgType, fields, notes);
        }
    }

    private void addMsgTypeEnumValue(
            final String msgType, final Map<String, FieldDef> fields, final List<ConversionNote> notes) {

        final FieldDef msgTypeField = fields.get("MsgType");
        if (msgTypeField == null || msgTypeField.values().isEmpty()) {
            return;
        }
        for (final EnumValue value : msgTypeField.values()) {
            if (value.representation().equals(msgType)) {
                return;
            }
        }
        final List<EnumValue> values = new ArrayList<>(msgTypeField.values());
        final String description = SessionContract.msgTypeDescription(msgType);
        values.add(new EnumValue(msgType, description));
        fields.put("MsgType", msgTypeField.withValues(values));
        notes.add(ConversionNote.added(
                RULE_SESSION_MESSAGE_MISSING, "field MsgType (35)",
                "<value enum=\"" + msgType + "\" description=\"" + description + "\"/>",
                WHY_SESSION_MESSAGE_MISSING));
    }

    private void declareIfMissing(
            final String name,
            final Map<String, FieldDef> fields,
            final List<ConversionNote> notes,
            final String rule,
            final String why) {

        if (fields.containsKey(name)) {
            return;
        }
        final FieldDef standard = SessionContract.standardField(name);
        if (standard == null) {
            return;
        }
        fields.put(name, standard);
        notes.add(ConversionNote.added(
                rule, "fields",
                "<field number=\"" + standard.number() + "\" name=\"" + standard.name()
                        + "\" type=\"" + standard.type() + "\"/>",
                why));
    }

    // -----------------------------------------------------------------------------------------
    // DATA fields
    // -----------------------------------------------------------------------------------------

    /**
     * Artio validates DATA fields per aggregate and only against that aggregate's <em>direct</em>
     * field entries, so the length partner has to sit next to the DATA field, not in a component.
     *
     * <p>{@code reachableTags} is every tag the enclosing root already reaches (Artio's duplicate
     * walk spans the whole message, groups and components included). A partner that is in that
     * set cannot be inserted beside the DATA field without becoming a duplicate, so the DATA
     * field is retyped instead, exactly as when no partner is declared at all.</p>
     */
    private List<DictionaryEntry> fixDataFields(
            final List<DictionaryEntry> entries,
            final Map<String, FieldDef> fields,
            final String owner,
            final List<ConversionNote> notes,
            final Set<Integer> reachableTags) {

        final Set<String> directFields = new LinkedHashSet<>();
        for (final DictionaryEntry entry : entries) {
            if (entry instanceof FieldRef field) {
                directFields.add(field.name());
            }
        }

        final List<DictionaryEntry> result = new ArrayList<>();
        for (final DictionaryEntry entry : entries) {
            if (entry instanceof GroupRef group) {
                result.add(group.withChildren(fixDataFields(
                        group.children(), fields, owner + " group " + group.name(), notes, reachableTags)));
                continue;
            }
            if (!(entry instanceof FieldRef fieldRef)) {
                result.add(entry);
                continue;
            }
            final FieldDef declared = fields.get(fieldRef.name());
            if (declared == null || !ArtioFieldTypes.isDataBased(declared.type())) {
                result.add(entry);
                continue;
            }
            if (lengthPartner(declared.name(), directFields, fields) != null) {
                result.add(entry);
                continue;
            }
            final FieldDef declaredPartner = declaredLengthPartner(declared.name(), fields);
            if (declaredPartner == null) {
                fields.put(declared.name(), declared.withType(ArtioFieldTypes.FALLBACK));
                result.add(entry);
                notes.add(new ConversionNote(
                        RULE_DATA_FIELD_LENGTH, describe(declared), declared.type(),
                        ArtioFieldTypes.FALLBACK,
                        WHY_DATA_FIELD_LENGTH + "; no <name>Len or <name>Length field is declared "
                                + "anywhere in the dictionary, so the field is retyped instead"));
            } else if (reachableTags.contains(declaredPartner.number())) {
                fields.put(declared.name(), declared.withType(ArtioFieldTypes.FALLBACK));
                result.add(entry);
                notes.add(new ConversionNote(
                        RULE_DATA_FIELD_LENGTH, describe(declared), declared.type(),
                        ArtioFieldTypes.FALLBACK,
                        WHY_DATA_FIELD_LENGTH + "; " + declaredPartner.name() + " is already reached "
                                + "elsewhere in " + owner + " (through a component or a group), so a "
                                + "second copy beside " + declared.name() + " would be the duplicate "
                                + "field Artio also refuses; the field is retyped instead"));
            } else {
                result.add(new FieldRef(declaredPartner.name(), false));
                directFields.add(declaredPartner.name());
                reachableTags.add(declaredPartner.number());
                result.add(entry);
                notes.add(ConversionNote.added(
                        RULE_DATA_FIELD_LENGTH, owner,
                        "<field name=\"" + declaredPartner.name() + "\" required=\"N\"/> before "
                                + declared.name(),
                        WHY_DATA_FIELD_LENGTH));
            }
        }
        return result;
    }

    private static String lengthPartner(
            final String dataFieldName,
            final Set<String> directFields,
            final Map<String, FieldDef> fields) {

        for (final String suffix : List.of("Length", "Len")) {
            final String candidate = dataFieldName + suffix;
            final FieldDef declared = fields.get(candidate);
            if (directFields.contains(candidate) && declared != null
                    && ArtioFieldTypes.isLengthLike(declared.type())) {
                return candidate;
            }
        }
        return null;
    }

    private static FieldDef declaredLengthPartner(
            final String dataFieldName, final Map<String, FieldDef> fields) {

        for (final String suffix : List.of("Length", "Len")) {
            final FieldDef declared = fields.get(dataFieldName + suffix);
            if (declared != null && ArtioFieldTypes.isLengthLike(declared.type())) {
                return declared;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------

    /** Field names an aggregate reaches directly or through a component; groups are their own scope. */
    private static void collectFieldNames(
            final List<DictionaryEntry> entries,
            final Map<String, ComponentDef> components,
            final Set<String> into,
            final Set<String> visiting) {

        for (final DictionaryEntry entry : entries) {
            switch (entry) {
                case FieldRef field -> into.add(field.name());
                case ComponentRef reference -> {
                    final ComponentDef component = components.get(reference.name());
                    if (component != null && visiting.add(component.name())) {
                        collectFieldNames(component.entries(), components, into, visiting);
                        visiting.remove(component.name());
                    }
                }
                case GroupRef group -> into.add(group.name());
            }
        }
    }

    private static String describe(final FieldDef field) {
        return "field " + field.name() + " (" + field.number() + ")";
    }

    private static String describe(final MessageDef message) {
        return "message " + message.name() + " (35=" + message.msgType() + ")";
    }
}
