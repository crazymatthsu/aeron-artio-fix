package com.demo.artio.qfj;

/**
 * The FIX versions this counterparty speaks.
 *
 * <p>Deliberately a local enum rather than {@code com.demo.artio.engine.FixVersion}: this module is
 * "the other FIX engine" and must not depend on the Artio module, so that a test proving the
 * counterparty works cannot accidentally be proving Artio works. Tests that need both map between
 * them.
 */
public enum QfjVersion
{
    /**
     * FIX 4.2. Its {@code ExecutionReport} still carries {@code ExecTransType(20)} and its
     * {@code NewOrderSingle} still requires {@code HandlInst(21)}.
     */
    FIX42("FIX.4.2"),

    /**
     * FIX 4.4. {@code ExecTransType(20)} is gone, {@code HandlInst(21)} is optional, and a fill is
     * reported with {@code ExecType(150)=F} rather than {@code 2}.
     */
    FIX44("FIX.4.4");

    private final String beginString;

    QfjVersion(final String beginString)
    {
        this.beginString = beginString;
    }

    /** @return the value of FIX tag 8, e.g. {@code FIX.4.2}. */
    public String beginString()
    {
        return beginString;
    }

    /** @return true if this version still has {@code ExecTransType(20)}. */
    public boolean hasExecTransType()
    {
        return this == FIX42;
    }

    /** @return true if {@code HandlInst(21)} is a required field of {@code NewOrderSingle}. */
    public boolean requiresHandlInst()
    {
        return this == FIX42;
    }

    /**
     * @return the {@code ExecType(150)} value that means "this order traded": {@code 2} (Fill) in
     * FIX 4.2, {@code F} (Trade) in FIX 4.4, where {@code 2} is deprecated.
     */
    public char fillExecType()
    {
        return this == FIX42 ? '2' : 'F';
    }

    /**
     * The {@code ExecType(150)} value this version uses for one lifecycle event.
     *
     * <p>Only the two trade events differ: FIX 4.2 distinguishes {@code 1} (Partial fill) from
     * {@code 2} (Fill), while FIX 4.4 replaced both with {@code F} (Trade) and leaves the
     * distinction to {@code OrdStatus(39)}. The other three are the same character in both.
     *
     * @param event the lifecycle event.
     * @return the {@code ExecType(150)} character to write.
     */
    public char execType(final ExecEvent event)
    {
        return switch (event)
        {
            case ACKNOWLEDGED -> '0';
            case PARTIALLY_FILLED -> this == FIX42 ? '1' : fillExecType();
            case FILLED -> fillExecType();
            case REPLACED -> '5';
            case CANCELED -> '4';
        };
    }

    /**
     * Looks a version up by its {@code BeginString} or its enum name, so both {@code FIX.4.2} and
     * {@code FIX42} work on the command line.
     *
     * @param value the begin string or enum name, case insensitive.
     * @return the matching version.
     * @throws IllegalArgumentException if no version matches.
     */
    public static QfjVersion ofBeginString(final String value)
    {
        for (final QfjVersion version : values())
        {
            if (version.beginString.equalsIgnoreCase(value) || version.name().equalsIgnoreCase(value))
            {
                return version;
            }
        }
        throw new IllegalArgumentException(
            "Unsupported FIX version '" + value + "'; expected one of FIX.4.2, FIX.4.4");
    }

    /**
     * What an execution report says happened, named semantically rather than as a wire value: the
     * character that carries it is version dependent and is {@link QfjVersion#execType(ExecEvent)}'s
     * business, not the caller's.
     */
    public enum ExecEvent
    {
        /** The venue acknowledged a new order and is working it. */
        ACKNOWLEDGED,

        /** Part of the order traded; more is still working. */
        PARTIALLY_FILLED,

        /** The rest of the order traded; nothing is left. */
        FILLED,

        /** A cancel/replace was applied to the order. */
        REPLACED,

        /** The order was cancelled. */
        CANCELED
    }
}
