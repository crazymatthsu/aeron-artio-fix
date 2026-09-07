package com.demo.artio.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link SessionListener} that records what it was told, so a test on another thread can assert
 * on the lifecycle rather than only on the message stream.
 */
final class RecordingSessionListener implements SessionListener
{
    private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SessionKey> acquired = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    @Override
    public void onSessionAcquired(final SessionKey session)
    {
        acquired.add(session);
        events.add("acquired:" + session.remoteCompId());
    }

    @Override
    public void onLogon(final SessionKey session)
    {
        events.add("logon:" + session.remoteCompId());
    }

    @Override
    public void onLogout(final SessionKey session)
    {
        events.add("logout:" + session.remoteCompId());
    }

    @Override
    public void onDisconnect(final SessionKey session, final String reason)
    {
        events.add("disconnect:" + session.remoteCompId() + ':' + reason);
    }

    @Override
    public void onTimeout(final SessionKey session)
    {
        events.add("timeout:" + session.remoteCompId());
    }

    @Override
    public void onError(final Throwable error)
    {
        errors.add(error);
        events.add("error:" + error.getClass().getSimpleName());
    }

    List<String> events()
    {
        return List.copyOf(events);
    }

    List<SessionKey> acquired()
    {
        return List.copyOf(acquired);
    }

    List<Throwable> errors()
    {
        return List.copyOf(errors);
    }

    boolean sawEventStartingWith(final String prefix)
    {
        return events.stream().anyMatch(event -> event.startsWith(prefix));
    }
}
