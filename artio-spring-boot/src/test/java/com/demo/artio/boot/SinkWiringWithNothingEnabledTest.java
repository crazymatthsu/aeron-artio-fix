package com.demo.artio.boot;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.demo.artio.engine.FixMessageSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code bridge.enabled=false} with {@code artio.log-messages=false} leaves nothing to deliver
 * messages to. The sink bean must still exist (the runtime needs one) and must be the engine's
 * no-op, not a composite with no delegates or a null.
 */
@SpringBootTest(properties = {
    "artio.enabled=false",
    "bridge.enabled=false",
    "artio.log-messages=false",
    "bridge.stats-log-interval-ms=0"})
class SinkWiringWithNothingEnabledTest
{
    @Autowired
    private FixMessageSink sink;

    @Test
    void withNoPublisherAndNoLoggingTheSinkIsTheEnginesNoOp()
    {
        assertSame(FixMessageSink.NO_OP, sink);
    }
}
