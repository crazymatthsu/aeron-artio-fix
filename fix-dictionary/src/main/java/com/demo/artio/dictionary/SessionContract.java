package com.demo.artio.dictionary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fields Artio's session layer needs a dictionary to declare.
 *
 * <p>Artio's generated {@code HeaderEncoder}, {@code HeaderDecoder} and admin-message codecs
 * implement {@code uk.co.real_logic.artio.builder.SessionHeaderEncoder},
 * {@code uk.co.real_logic.artio.decoder.SessionHeaderDecoder} and the
 * {@code Abstract*Encoder}/{@code Abstract*Decoder} interfaces. Those interfaces are fixed;
 * the generator only emits an accessor for a field the dictionary declares on the aggregate.
 * A field missing here therefore produces a <em>javac</em> failure such as</p>
 *
 * <pre>
 * HeaderEncoder is not abstract and does not override abstract method
 *     hasSenderSubID() in SessionHeaderEncoder
 * RejectEncoder is not abstract and does not override abstract method
 *     sessionRejectReason(int) in AbstractRejectEncoder
 * </pre>
 *
 * <p>and not a dictionary-parser error, so it only shows up once the generated code is compiled.
 * Each entry below was found by deleting exactly one field from a working dictionary and
 * re-running the generator plus javac; see {@code docs/02-quickfixj-to-artio-dictionary.md}.</p>
 *
 * <p>Fields Artio guards with a {@code supportsXxx()} method are <em>not</em> listed: the
 * generator emits {@code supportsUsername() { return false; }} when Logon has no
 * {@code Username}, so a FIX 4.2 dictionary compiles without it. That is why an Artio FIX 4.2
 * session cannot send Logon credentials -- see the gotchas in docs/02.</p>
 */
public final class SessionContract {

    /** The msgtypes of the seven session-level messages Artio drives itself. */
    public static final Set<String> ADMIN_MSG_TYPES = Set.of("0", "1", "2", "3", "4", "5", "A");

    /** Optional header fields whose absence breaks {@code Header{Encoder,Decoder}}. */
    public static final List<String> HEADER_FIELDS = List.of(
            "SenderSubID", "SenderLocationID", "TargetSubID", "TargetLocationID",
            "PossDupFlag", "PossResend", "OrigSendingTime", "LastMsgSeqNumProcessed");

    /** Header fields every FIX dictionary already has; listed so a synthesised header is complete. */
    public static final List<String> HEADER_REQUIRED_FIELDS = List.of(
            "BeginString", "BodyLength", "MsgType", "SenderCompID", "TargetCompID",
            "MsgSeqNum", "SendingTime");

    /** Fields whose absence breaks the codec of the named admin message, keyed by msgtype. */
    private static final Map<String, List<String>> MESSAGE_FIELDS = Map.of(
            "0", List.of("TestReqID"),
            "1", List.of("TestReqID"),
            "2", List.of("BeginSeqNo", "EndSeqNo"),
            "3", List.of("RefSeqNum", "RefTagID", "SessionRejectReason", "Text"),
            "4", List.of("GapFillFlag", "NewSeqNo"),
            "5", List.of("Text"),
            "A", List.of("EncryptMethod", "HeartBtInt", "ResetSeqNumFlag"));

    /** The standard name of each admin message, used when one has to be synthesised. */
    private static final Map<String, String> MESSAGE_NAMES = Map.of(
            "0", "Heartbeat",
            "1", "TestRequest",
            "2", "ResendRequest",
            "3", "Reject",
            "4", "SequenceReset",
            "5", "Logout",
            "A", "Logon");

    /** The {@code <value>} description used on tag 35 when an admin message has to be added. */
    private static final Map<String, String> MSG_TYPE_DESCRIPTIONS = Map.of(
            "0", "HEARTBEAT",
            "1", "TEST_REQUEST",
            "2", "RESEND_REQUEST",
            "3", "REJECT",
            "4", "SEQUENCE_RESET",
            "5", "LOGOUT",
            "A", "LOGON");

    /** Which of an admin message's fields are {@code required="Y"} in standard FIX. */
    private static final Set<String> REQUIRED_IN_ADMIN_MESSAGE = Set.of(
            "BeginSeqNo", "EndSeqNo", "RefSeqNum", "NewSeqNo", "EncryptMethod", "HeartBtInt");

    /**
     * Standard tag numbers and Artio types for every session field, so a field the converter has
     * to add to the header or to an admin message can also be declared in {@code <fields>}.
     * Taken from artio-session-codecs' own {@code session_dictionary.xml}.
     */
    private static final Map<String, FieldDef> STANDARD_FIELDS = standardFields();

    private SessionContract() {
    }

    public static List<String> messageFields(final String msgType) {
        return MESSAGE_FIELDS.getOrDefault(msgType, List.of());
    }

    public static String messageName(final String msgType) {
        return MESSAGE_NAMES.get(msgType);
    }

    public static String msgTypeDescription(final String msgType) {
        return MSG_TYPE_DESCRIPTIONS.get(msgType);
    }

    public static boolean isRequiredInAdminMessage(final String fieldName) {
        return REQUIRED_IN_ADMIN_MESSAGE.contains(fieldName);
    }

    /** The standard declaration for a session field, or null when the name is not one. */
    public static FieldDef standardField(final String name) {
        return STANDARD_FIELDS.get(name);
    }

    private static Map<String, FieldDef> standardFields() {
        final Map<String, FieldDef> fields = new LinkedHashMap<>();
        declare(fields, 8, "BeginString", "STRING");
        declare(fields, 9, "BodyLength", "LENGTH");
        declare(fields, 10, "CheckSum", "STRING");
        declare(fields, 34, "MsgSeqNum", "SEQNUM");
        declare(fields, 35, "MsgType", "STRING");
        declare(fields, 43, "PossDupFlag", "BOOLEAN");
        declare(fields, 49, "SenderCompID", "STRING");
        declare(fields, 50, "SenderSubID", "STRING");
        declare(fields, 52, "SendingTime", "UTCTIMESTAMP");
        declare(fields, 56, "TargetCompID", "STRING");
        declare(fields, 57, "TargetSubID", "STRING");
        declare(fields, 97, "PossResend", "BOOLEAN");
        declare(fields, 122, "OrigSendingTime", "UTCTIMESTAMP");
        declare(fields, 142, "SenderLocationID", "STRING");
        declare(fields, 143, "TargetLocationID", "STRING");
        declare(fields, 369, "LastMsgSeqNumProcessed", "SEQNUM");
        declare(fields, 112, "TestReqID", "STRING");
        declare(fields, 7, "BeginSeqNo", "SEQNUM");
        declare(fields, 16, "EndSeqNo", "SEQNUM");
        declare(fields, 45, "RefSeqNum", "SEQNUM");
        declare(fields, 371, "RefTagID", "INT");
        declare(fields, 372, "RefMsgType", "STRING");
        declare(fields, 373, "SessionRejectReason", "INT");
        declare(fields, 58, "Text", "STRING");
        declare(fields, 123, "GapFillFlag", "BOOLEAN");
        declare(fields, 36, "NewSeqNo", "SEQNUM");
        declare(fields, 98, "EncryptMethod", "INT");
        declare(fields, 108, "HeartBtInt", "INT");
        declare(fields, 141, "ResetSeqNumFlag", "BOOLEAN");
        return fields;
    }

    private static void declare(
            final Map<String, FieldDef> fields, final int number, final String name, final String type) {
        fields.put(name, new FieldDef(number, name, type));
    }
}
