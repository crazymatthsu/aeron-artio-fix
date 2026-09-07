package com.demo.artio.dictionary;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a QuickFIX/J (or Artio -- the schema is the same) FIX dictionary into {@link FixDictionary}.
 *
 * <p>JDK DOM only, no external XML library. External entities and DTD loading are switched off:
 * a dictionary is data, and these files are often pulled from a counterparty.</p>
 */
public final class QuickFixDictionaryReader {

    public FixDictionary read(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        } catch (final DictionaryReadException e) {
            throw new DictionaryReadException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public FixDictionary read(final InputStream in) throws IOException {
        final Document document = parse(in);
        final Element root = document.getDocumentElement();
        if (root == null || !"fix".equals(root.getNodeName())) {
            throw new DictionaryReadException(
                    "not a FIX dictionary: the root element is "
                            + (root == null ? "missing" : "<" + root.getNodeName() + ">") + ", expected <fix>");
        }

        return new FixDictionary(
                attributeOrNull(root, "type"),
                requiredInt(root, "major"),
                requiredInt(root, "minor"),
                attributeOrNull(root, "servicepack"),
                readEntries(child(root, "header")),
                readEntries(child(root, "trailer")),
                readMessages(child(root, "messages")),
                readComponents(child(root, "components")),
                readFields(child(root, "fields")));
    }

    private Document parse(final InputStream in) throws IOException {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(in);
        } catch (final ParserConfigurationException | SAXException e) {
            throw new DictionaryReadException("malformed dictionary XML: " + e.getMessage(), e);
        }
    }

    private List<MessageDef> readMessages(final Element messages) {
        final List<MessageDef> result = new ArrayList<>();
        for (final Element message : childElements(messages, "message")) {
            result.add(new MessageDef(
                    requiredAttribute(message, "name"),
                    requiredAttribute(message, "msgtype"),
                    attributeOrNull(message, "msgcat"),
                    readEntries(message)));
        }
        return result;
    }

    private List<ComponentDef> readComponents(final Element components) {
        final List<ComponentDef> result = new ArrayList<>();
        for (final Element component : childElements(components, "component")) {
            result.add(new ComponentDef(requiredAttribute(component, "name"), readEntries(component)));
        }
        return result;
    }

    private List<FieldDef> readFields(final Element fields) {
        final List<FieldDef> result = new ArrayList<>();
        for (final Element field : childElements(fields, "field")) {
            final List<EnumValue> values = new ArrayList<>();
            for (final Element value : childElements(field, "value")) {
                values.add(new EnumValue(
                        requiredAttribute(value, "enum"),
                        // A missing description is legal here and repaired by the converter;
                        // Artio's parser throws "Empty item for: description" on it.
                        attributeOrNull(value, "description")));
            }
            result.add(new FieldDef(
                    requiredInt(field, "number"),
                    requiredAttribute(field, "name"),
                    requiredAttribute(field, "type"),
                    values));
        }
        return result;
    }

    private List<DictionaryEntry> readEntries(final Element parent) {
        final List<DictionaryEntry> entries = new ArrayList<>();
        if (parent == null) {
            return entries;
        }
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            final String name = element.getAttribute("name");
            if (name.isBlank()) {
                // Artio skips a nameless entry outright; do the same so the model matches.
                continue;
            }
            final boolean required = "Y".equals(element.getAttribute("required"));
            switch (element.getNodeName()) {
                case "field" -> entries.add(new FieldRef(name, required));
                case "component" -> entries.add(new ComponentRef(name, required));
                case "group" -> entries.add(new GroupRef(name, required, readEntries(element)));
                default -> {
                    // <value> under a <field>, or anything Artio ignores.
                }
            }
        }
        return entries;
    }

    private static Element child(final Element parent, final String name) {
        final List<Element> found = childElements(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> childElements(final Element parent, final String name) {
        final List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && element.getNodeName().equals(name)) {
                result.add(element);
            }
        }
        return result;
    }

    private static String attributeOrNull(final Element element, final String name) {
        final String value = element.getAttribute(name);
        return value.isBlank() ? null : value;
    }

    private static String requiredAttribute(final Element element, final String name) {
        final String value = attributeOrNull(element, name);
        if (value == null) {
            throw new DictionaryReadException(
                    "<" + element.getNodeName() + "> is missing the '" + name + "' attribute");
        }
        return value;
    }

    private static int requiredInt(final Element element, final String name) {
        final String value = requiredAttribute(element, name);
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            throw new DictionaryReadException(
                    "<" + element.getNodeName() + "> attribute '" + name + "' is not a number: " + value, e);
        }
    }

    /** Thrown when the XML is not a readable FIX dictionary. */
    public static final class DictionaryReadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DictionaryReadException(final String message) {
            super(message);
        }

        public DictionaryReadException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
