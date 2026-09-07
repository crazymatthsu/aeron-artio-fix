package com.demo.artio.qfj;

/**
 * The FIX tag numbers this module reads and writes.
 *
 * <p>Raw numbers rather than {@code quickfix.field.*} classes on purpose: the field classes are
 * version-flavoured (FIX 4.2 calls tag 32 {@code LastShares}, FIX 4.4 calls it {@code LastQty}),
 * while a tag number is a tag number. Messages are therefore built with
 * {@code Message.setString(tag, value)} and work unchanged on both versions.
 */
public final class Tags
{
    private Tags()
    {
    }

    /** BeginString. */
    public static final int BEGIN_STRING = 8;
    /** BodyLength. */
    public static final int BODY_LENGTH = 9;
    /** CheckSum. */
    public static final int CHECK_SUM = 10;
    /** AvgPx. */
    public static final int AVG_PX = 6;
    /** CumQty. */
    public static final int CUM_QTY = 14;
    /** ExecID. */
    public static final int EXEC_ID = 17;
    /** ExecTransType; FIX 4.2 only. */
    public static final int EXEC_TRANS_TYPE = 20;
    /** HandlInst. */
    public static final int HANDL_INST = 21;
    /** LastPx. */
    public static final int LAST_PX = 31;
    /** LastShares (FIX 4.2) / LastQty (FIX 4.4). */
    public static final int LAST_QTY = 32;
    /** MsgSeqNum. */
    public static final int MSG_SEQ_NUM = 34;
    /** MsgType. */
    public static final int MSG_TYPE = 35;
    /** OrderID. */
    public static final int ORDER_ID = 37;
    /** OrderQty. */
    public static final int ORDER_QTY = 38;
    /** OrdStatus. */
    public static final int ORD_STATUS = 39;
    /** OrdType. */
    public static final int ORD_TYPE = 40;
    /** OrigClOrdID. */
    public static final int ORIG_CL_ORD_ID = 41;
    /** Price. */
    public static final int PRICE = 44;
    /** SenderCompID. */
    public static final int SENDER_COMP_ID = 49;
    /** SendingTime. */
    public static final int SENDING_TIME = 52;
    /** Side. */
    public static final int SIDE = 54;
    /** Symbol. */
    public static final int SYMBOL = 55;
    /** TargetCompID. */
    public static final int TARGET_COMP_ID = 56;
    /** Text. */
    public static final int TEXT = 58;
    /** TimeInForce. */
    public static final int TIME_IN_FORCE = 59;
    /** TransactTime. */
    public static final int TRANSACT_TIME = 60;
    /** ClOrdID. */
    public static final int CL_ORD_ID = 11;
    /** LeavesQty. */
    public static final int LEAVES_QTY = 151;
    /** ExecType. */
    public static final int EXEC_TYPE = 150;

    /** {@code MsgType} of a NewOrderSingle. */
    public static final String MSG_TYPE_NEW_ORDER_SINGLE = "D";
    /** {@code MsgType} of an ExecutionReport. */
    public static final String MSG_TYPE_EXECUTION_REPORT = "8";
    /** {@code MsgType} of an OrderCancelReplaceRequest. */
    public static final String MSG_TYPE_CANCEL_REPLACE = "G";
    /** {@code MsgType} of an OrderCancelRequest. */
    public static final String MSG_TYPE_CANCEL = "F";
    /** {@code MsgType} of an OrderCancelReject. */
    public static final String MSG_TYPE_CANCEL_REJECT = "9";
}
