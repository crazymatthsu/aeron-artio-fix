package com.demo.artio.qfj;

/**
 * The command line of {@link QfjMain}, parsed into a value so it can be unit tested without
 * starting a FIX engine.
 *
 * @param role                 {@code initiator} or {@code acceptor}, the one positional argument.
 * @param host                 {@code --host}; connect host or bind address.
 * @param port                 {@code --port}; required.
 * @param version              {@code --version}; {@code FIX.4.2} or {@code FIX.4.4}.
 * @param senderCompId         {@code --sender}.
 * @param targetCompId         {@code --target}.
 * @param runScenario          {@code --scenario orders} rather than {@code none}.
 * @param heartbeatIntervalSec {@code --heartbeat}.
 * @param screenLog            {@code --screen-log}, a flag: also print QuickFIX/J's own log.
 */
public record CliArgs(
    QfjRole role,
    String host,
    int port,
    QfjVersion version,
    String senderCompId,
    String targetCompId,
    boolean runScenario,
    int heartbeatIntervalSec,
    boolean screenLog)
{
    /** Printed on a parse error and by {@code --help}. */
    public static final String USAGE = """
        Usage: <initiator|acceptor> [options]

          --host <host>        host to connect to (initiator) or bind to (acceptor). Default localhost
          --port <port>        port to connect to or bind to. Required
          --version <version>  FIX.4.2 or FIX.4.4. Default FIX.4.2
          --sender <compId>    this engine's SenderCompID. Default QFJ
          --target <compId>    the counterparty's TargetCompID. Default ARTIO
          --scenario <name>    orders (3 new orders, 1 replace, 1 cancel) or none. Default none
          --heartbeat <secs>   HeartBtInt. Default 10
          --screen-log         also print QuickFIX/J's own message log
          --help               print this and exit

        Examples:
          initiator --host localhost --port 9880 --version FIX.4.2 --sender QFJ --target ARTIO \
        --scenario orders
          acceptor  --port 9881 --version FIX.4.4 --sender QFJ --target ARTIO
        """;

    /** The scenario name that runs {@link OrderScenario#DEFAULT}. */
    public static final String SCENARIO_ORDERS = "orders";

    /** The scenario name that sends nothing. */
    public static final String SCENARIO_NONE = "none";

    /**
     * Parses a command line.
     *
     * @param args the raw arguments.
     * @return the parsed value.
     * @throws IllegalArgumentException on an unknown option, a missing value, a bad number or a
     *                                  missing {@code --port}. The message names the problem. Range
     *                                  checks - the port, the heartbeat, equal comp ids - are
     *                                  {@link QfjConfig}'s and surface from {@link #toConfig()}.
     */
    public static CliArgs parse(final String[] args)
    {
        if (args == null || args.length == 0)
        {
            throw new IllegalArgumentException("No arguments: expected 'initiator' or 'acceptor' first");
        }

        final QfjRole role = switch (args[0].toLowerCase())
        {
            case "initiator" -> QfjRole.INITIATOR;
            case "acceptor" -> QfjRole.ACCEPTOR;
            default -> throw new IllegalArgumentException(
                "First argument must be 'initiator' or 'acceptor' but was '" + args[0] + "'");
        };

        String host = "localhost";
        Integer port = null;
        QfjVersion version = QfjVersion.FIX42;
        String sender = "QFJ";
        String target = "ARTIO";
        boolean runScenario = false;
        int heartbeat = 10;
        boolean screenLog = false;

        for (int i = 1; i < args.length; i++)
        {
            final String option = args[i];
            switch (option)
            {
                case "--screen-log" -> screenLog = true;
                case "--host" -> host = value(args, ++i, option);
                case "--port" -> port = intValue(args, ++i, option);
                case "--version" -> version = QfjVersion.ofBeginString(value(args, ++i, option));
                case "--sender" -> sender = value(args, ++i, option);
                case "--target" -> target = value(args, ++i, option);
                case "--heartbeat" -> heartbeat = intValue(args, ++i, option);
                case "--scenario" ->
                {
                    final String scenario = value(args, ++i, option).toLowerCase();
                    runScenario = switch (scenario)
                    {
                        case SCENARIO_ORDERS -> true;
                        case SCENARIO_NONE -> false;
                        default -> throw new IllegalArgumentException(
                            "Unknown scenario '" + scenario + "'; expected '" + SCENARIO_ORDERS +
                                "' or '" + SCENARIO_NONE + "'");
                    };
                }
                default -> throw new IllegalArgumentException("Unknown option '" + option + "'");
            }
        }

        if (port == null)
        {
            // Absent, as opposed to present and out of range: that is QfjConfig's complaint.
            throw new IllegalArgumentException("--port is required");
        }
        return new CliArgs(role, host, port, version, sender, target, runScenario, heartbeat, screenLog);
    }

    /**
     * @param args the raw arguments.
     * @return true if {@code --help} or {@code -h} appears anywhere.
     */
    public static boolean isHelp(final String[] args)
    {
        if (args == null)
        {
            return false;
        }
        for (final String arg : args)
        {
            if ("--help".equals(arg) || "-h".equals(arg))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the QuickFIX/J configuration these arguments describe.
     * @throws IllegalArgumentException if a value is out of range; see {@link QfjConfig}.
     */
    public QfjConfig toConfig()
    {
        return QfjConfig.builder(role)
            .version(version)
            .address(host, port)
            .senderCompId(senderCompId)
            .targetCompId(targetCompId)
            .heartbeatIntervalSec(heartbeatIntervalSec)
            .screenLog(screenLog)
            .build();
    }

    private static String value(final String[] args, final int index, final String option)
    {
        if (index >= args.length)
        {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static int intValue(final String[] args, final int index, final String option)
    {
        final String raw = value(args, index, option);
        try
        {
            return Integer.parseInt(raw);
        }
        catch (final NumberFormatException e)
        {
            throw new IllegalArgumentException(option + " needs a number but got '" + raw + "'");
        }
    }
}
