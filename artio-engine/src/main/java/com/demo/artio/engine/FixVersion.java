package com.demo.artio.engine;

import uk.co.real_logic.artio.dictionary.FixDictionary;

/**
 * The FIX versions this engine can speak, each bound to the Artio {@link FixDictionary}
 * implementation {@code :fix-codecs} generates for it.
 *
 * <p>Artio bundles session codecs for FIX 4.4 only, and a session validates the counterparty's
 * {@code BeginString(8)} against the dictionary it was handed. FIX 4.2 therefore only works
 * because {@code com.demo.artio.fix42.FixDictionaryImpl} exists; see
 * {@code docs/02-quickfixj-to-artio-dictionary.md} section 5.1.
 */
public enum FixVersion
{
    /** FIX 4.2. Flat encoders (no components), and a {@code Logon} with no Username/Password. */
    FIX42("FIX.4.2", com.demo.artio.fix42.FixDictionaryImpl.class),

    /** FIX 4.4. Encoders reach Symbol/OrderQty through the Instrument and OrderQtyData components. */
    FIX44("FIX.4.4", com.demo.artio.fix44.FixDictionaryImpl.class);

    private final String beginString;
    private final Class<? extends FixDictionary> dictionary;

    FixVersion(final String beginString, final Class<? extends FixDictionary> dictionary)
    {
        this.beginString = beginString;
        this.dictionary = dictionary;
    }

    /** The value of FIX tag 8 for this version, e.g. {@code FIX.4.2}. */
    public String beginString()
    {
        return beginString;
    }

    /**
     * The generated dictionary class, for {@code EngineConfiguration.acceptorfixDictionary(Class)}
     * and {@code SessionConfiguration.Builder.fixDictionary(Class)}.
     */
    public Class<? extends FixDictionary> dictionary()
    {
        return dictionary;
    }

    /**
     * Looks a version up by its {@code BeginString}, accepting either form used on the command
     * line ({@code FIX.4.2} or {@code FIX42}).
     *
     * @param value the begin string or enum name, case insensitive.
     * @return the matching version.
     * @throws IllegalArgumentException if no version matches.
     */
    public static FixVersion ofBeginString(final String value)
    {
        for (final FixVersion version : values())
        {
            if (version.beginString.equalsIgnoreCase(value) || version.name().equalsIgnoreCase(value))
            {
                return version;
            }
        }
        throw new IllegalArgumentException(
            "Unsupported FIX version '" + value + "'; expected one of FIX.4.2, FIX.4.4");
    }
}
