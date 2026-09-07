package com.demo.artio.testharness;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A {@link CommandRunner} that answers from a script instead of forking, and
 * remembers every command it was asked to run.
 *
 * <p>Rules are tried in the order they were added; the first whose matcher
 * accepts the command answers it. A command no rule matches fails with an
 * {@link IOException}, which is what the real runner does for a binary that is
 * not installed - so a test scripts only the tools its machine is pretending
 * to have, and everything else is absent.
 */
final class ScriptedCommandRunner implements CommandRunner {

    /** What a rule does when it matches: answers, or throws as a real command might. */
    @FunctionalInterface
    interface Answer {
        Result answer(List<String> command) throws IOException, InterruptedException;
    }

    private record Rule(Predicate<List<String>> matcher, Answer answer) {
    }

    private final List<Rule> rules = new ArrayList<>();
    private final List<List<String>> calls = new ArrayList<>();

    /** Matches a command that is exactly these words. */
    static Predicate<List<String>> exactly(String... words) {
        List<String> expected = List.of(words);
        return command -> command.equals(expected);
    }

    /** Matches a command containing these words consecutively, anywhere. */
    static Predicate<List<String>> containing(String... words) {
        List<String> expected = List.of(words);
        return command -> Collections.indexOfSubList(command, expected) >= 0;
    }

    ScriptedCommandRunner when(Predicate<List<String>> matcher, int exitCode, String output) {
        rules.add(new Rule(matcher, command -> new Result(exitCode, output)));
        return this;
    }

    ScriptedCommandRunner whenThrow(Predicate<List<String>> matcher,
                                    Supplier<? extends Exception> failure) {
        rules.add(new Rule(matcher, command -> {
            Exception e = failure.get();
            if (e instanceof IOException io) {
                throw io;
            }
            if (e instanceof InterruptedException ie) {
                throw ie;
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e);
        }));
        return this;
    }

    @Override
    public Result run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException {
        calls.add(List.copyOf(command));
        for (Rule rule : rules) {
            if (rule.matcher().test(command)) {
                return rule.answer().answer(command);
            }
        }
        throw new IOException("unscripted command (treated as a binary that is not installed): "
                + String.join(" ", command));
    }

    /** Every command run so far, in order. */
    List<List<String>> calls() {
        return List.copyOf(calls);
    }

    /** The first recorded command matching {@code matcher}, or a failure naming what was run. */
    List<String> firstCall(Predicate<List<String>> matcher) {
        return calls.stream().filter(matcher).findFirst()
                .orElseThrow(() -> new AssertionError("no such command was run; ran: " + calls));
    }

    /** The index of the first recorded command matching {@code matcher}, or -1. */
    int indexOf(Predicate<List<String>> matcher) {
        for (int i = 0; i < calls.size(); i++) {
            if (matcher.test(calls.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
