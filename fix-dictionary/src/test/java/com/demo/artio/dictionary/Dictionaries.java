package com.demo.artio.dictionary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Dictionary fixtures for the tests: the two bundled QuickFIX/J files, and a minimal but complete
 * FIX 4.2 dictionary that individual tests break in one specific way.
 */
final class Dictionaries {

    static final String BUNDLED_FIX42 = "/quickfixj/FIX42.xml";
    static final String BUNDLED_FIX44 = "/quickfixj/FIX44.xml";

    private Dictionaries() {
    }

    static FixDictionary readBundled(final String resource) {
        try (InputStream in = openBundled(resource)) {
            return new QuickFixDictionaryReader().read(in);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static InputStream openBundled(final String resource) {
        return Objects.requireNonNull(
                Dictionaries.class.getResourceAsStream(resource), "missing test resource " + resource);
    }

    static FixDictionary read(final String xml) {
        try (InputStream in = new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return new QuickFixDictionaryReader().read(in);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A FIX 4.2 dictionary that satisfies everything Artio needs, so a test can remove or corrupt
     * exactly one thing and attribute the resulting failure to it.
     */
    static String minimal(final String extraMessages, final String extraFields, final String components) {
        return HEADER_AND_TRAILER_OPEN
                + "  <messages>\n" + SESSION_MESSAGES + extraMessages + "  </messages>\n"
                + (components.isBlank() ? "" : "  <components>\n" + components + "  </components>\n")
                + "  <fields>\n" + SESSION_FIELDS + extraFields + "  </fields>\n"
                + "</fix>\n";
    }

    static String minimal() {
        return minimal("", "", "");
    }

    static String minimalWithField(final String fieldXml) {
        return minimal("", fieldXml, "");
    }

    private static final String HEADER_AND_TRAILER_OPEN = """
            <fix major="4" minor="2">
              <header>
                <field name="BeginString" required="Y"/>
                <field name="BodyLength" required="Y"/>
                <field name="MsgType" required="Y"/>
                <field name="SenderCompID" required="Y"/>
                <field name="TargetCompID" required="Y"/>
                <field name="MsgSeqNum" required="Y"/>
                <field name="SenderSubID" required="N"/>
                <field name="SenderLocationID" required="N"/>
                <field name="TargetSubID" required="N"/>
                <field name="TargetLocationID" required="N"/>
                <field name="PossDupFlag" required="N"/>
                <field name="PossResend" required="N"/>
                <field name="SendingTime" required="Y"/>
                <field name="OrigSendingTime" required="N"/>
                <field name="LastMsgSeqNumProcessed" required="N"/>
              </header>
              <trailer>
                <field name="CheckSum" required="Y"/>
              </trailer>
            """;

    static final String SESSION_MESSAGES = """
                <message name="Heartbeat" msgtype="0" msgcat="admin">
                  <field name="TestReqID" required="N"/>
                </message>
                <message name="TestRequest" msgtype="1" msgcat="admin">
                  <field name="TestReqID" required="Y"/>
                </message>
                <message name="ResendRequest" msgtype="2" msgcat="admin">
                  <field name="BeginSeqNo" required="Y"/>
                  <field name="EndSeqNo" required="Y"/>
                </message>
                <message name="Reject" msgtype="3" msgcat="admin">
                  <field name="RefSeqNum" required="Y"/>
                  <field name="RefTagID" required="N"/>
                  <field name="SessionRejectReason" required="N"/>
                  <field name="Text" required="N"/>
                </message>
                <message name="SequenceReset" msgtype="4" msgcat="admin">
                  <field name="GapFillFlag" required="N"/>
                  <field name="NewSeqNo" required="Y"/>
                </message>
                <message name="Logout" msgtype="5" msgcat="admin">
                  <field name="Text" required="N"/>
                </message>
                <message name="Logon" msgtype="A" msgcat="admin">
                  <field name="EncryptMethod" required="Y"/>
                  <field name="HeartBtInt" required="Y"/>
                  <field name="ResetSeqNumFlag" required="N"/>
                </message>
            """;

    static final String SESSION_FIELDS = """
                <field number="8" name="BeginString" type="STRING"/>
                <field number="9" name="BodyLength" type="LENGTH"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="34" name="MsgSeqNum" type="SEQNUM"/>
                <field number="35" name="MsgType" type="STRING">
                  <value enum="0" description="HEARTBEAT"/>
                  <value enum="1" description="TEST_REQUEST"/>
                  <value enum="2" description="RESEND_REQUEST"/>
                  <value enum="3" description="REJECT"/>
                  <value enum="4" description="SEQUENCE_RESET"/>
                  <value enum="5" description="LOGOUT"/>
                  <value enum="A" description="LOGON"/>
                </field>
                <field number="43" name="PossDupFlag" type="BOOLEAN"/>
                <field number="49" name="SenderCompID" type="STRING"/>
                <field number="50" name="SenderSubID" type="STRING"/>
                <field number="52" name="SendingTime" type="UTCTIMESTAMP"/>
                <field number="56" name="TargetCompID" type="STRING"/>
                <field number="57" name="TargetSubID" type="STRING"/>
                <field number="97" name="PossResend" type="BOOLEAN"/>
                <field number="122" name="OrigSendingTime" type="UTCTIMESTAMP"/>
                <field number="142" name="SenderLocationID" type="STRING"/>
                <field number="143" name="TargetLocationID" type="STRING"/>
                <field number="369" name="LastMsgSeqNumProcessed" type="SEQNUM"/>
                <field number="112" name="TestReqID" type="STRING"/>
                <field number="7" name="BeginSeqNo" type="SEQNUM"/>
                <field number="16" name="EndSeqNo" type="SEQNUM"/>
                <field number="45" name="RefSeqNum" type="SEQNUM"/>
                <field number="371" name="RefTagID" type="INT"/>
                <field number="373" name="SessionRejectReason" type="INT"/>
                <field number="58" name="Text" type="STRING"/>
                <field number="123" name="GapFillFlag" type="BOOLEAN"/>
                <field number="36" name="NewSeqNo" type="SEQNUM"/>
                <field number="98" name="EncryptMethod" type="INT"/>
                <field number="108" name="HeartBtInt" type="INT"/>
                <field number="141" name="ResetSeqNumFlag" type="BOOLEAN"/>
                <field number="11" name="ClOrdID" type="STRING"/>
                <field number="55" name="Symbol" type="STRING"/>
                <field number="95" name="RawDataLength" type="LENGTH"/>
                <field number="96" name="RawData" type="DATA"/>
            """;
}
