package com.github.claudecodegui.session;

import com.github.claudecodegui.permission.PermissionRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the live conversation runtimes shown by the Agent View panel.
 *
 * <p>Exactly one runtime is {@link SessionRuntime#isActive() active}: its adapter is
 * bound to the window and its messages are displayed. Detaching a runtime only
 * swaps its UI adapter; its {@link ClaudeSession} keeps streaming into
 * {@link SessionState}. All methods that mutate the active pointer must be invoked
 * on the EDT; read-only {@link #listJson(String)} may run off-EDT.</p>
 */
public final class AgentSessionRegistry {

    public static final int MAX_SESSIONS = 5;

    /**
     * Window-bound effects the registry cannot perform itself: creating a callback
     * adapter, swapping the active session into the window, and replaying the
     * runtime's transcript.
     */
    public interface Host {
        Project getProject();

        String getPermissionSessionId();

        SessionCallbackAdapter createUiAdapter(SessionRuntime runtime);

        void onRuntimeActivated(SessionRuntime runtime, SessionCallbackAdapter adapter);

        void onRuntimeDeactivated(SessionRuntime runtime);

        void onSessionListChanged();

        boolean isDisposed();
    }

    /** Allocates the runtime created by the panel's "new session" action. */
    public interface RuntimeFactory {
        SessionRuntime create(Project project, SessionRuntime source, String permissionSessionId);
    }

    private final Host host;
    private final RuntimeFactory runtimeFactory;
    private final Map<String, SessionRuntime> runtimes = new LinkedHashMap<>();
    private SessionRuntime active;
    private boolean disposed;

    public AgentSessionRegistry(Host host) {
        this(host, AgentSessionRegistry::createOwnedRuntime);
    }

    public AgentSessionRegistry(Host host, RuntimeFactory runtimeFactory) {
        this.host = host;
        this.runtimeFactory = runtimeFactory;
    }

    /**
     * Registers the window's freshly bound session as the initial runtime. Called
     * from {@code setupSessionCallbacks()} after every window-level session rebuild
     * (startup, new session, history load). The previous active runtime is retired;
     * background runtimes stay untouched.
     */
    public void registerWindowSession(ClaudeSession session, SessionCallbackAdapter adapter) {
        boolean replaced;
        synchronized (this) {
            if (disposed || session == null) {
                return;
            }
            SessionRuntime previous = active;
            if (previous != null && previous.getSession() == session) {
                attachAdapter(previous, adapter);
                return;
            }
            replaced = previous != null;
            if (previous != null) {
                // The rebuild already stopped the old session, so it must not linger
                // as an attachable row (or consume one of the MAX_SESSIONS slots).
                runtimes.remove(previous.getKey());
                retireActiveRuntime(previous);
            }
            SessionRuntime runtime = new SessionRuntime(UUID.randomUUID().toString(), session, null, false);
            runtimes.put(runtime.getKey(), runtime);
            attachAdapter(runtime, adapter);
            active = runtime;
        }
        if (replaced) {
            host.onSessionListChanged();
        }
    }

    /**
     * Makes {@code key} the displayed runtime and replays its transcript through the
     * host. Returns the runtime, or {@code null} when the key is unknown.
     */
    public SessionRuntime attach(String key) {
        SessionRuntime target;
        synchronized (this) {
            if (disposed) {
                return null;
            }
            target = runtimes.get(key);
        }
        if (target == null) {
            return null;
        }
        SessionCallbackAdapter adapter = host.createUiAdapter(target);
        SessionRuntime previous;
        boolean sameRuntime;
        synchronized (this) {
            if (disposed || runtimes.get(key) != target) {
                adapter.deactivate();
                adapter.dispose();
                return null;
            }
            previous = active;
            sameRuntime = previous == target;
            if (previous != null && !sameRuntime) {
                previous.setActive(false);
                SessionCallbackAdapter previousAdapter = previous.getUiAdapter();
                previous.setUiAdapter(null);
                if (previousAdapter != null) {
                    previousAdapter.deactivate();
                    previousAdapter.dispose();
                }
            }
            if (sameRuntime) {
                // Re-attaching the displayed session still replays it: the caller
                // started a frontend transition that only the replay's setSessionId
                // releases.
                SessionCallbackAdapter existing = target.getUiAdapter();
                if (existing != null) {
                    existing.deactivate();
                    existing.dispose();
                }
            }
            attachAdapter(target, adapter);
            active = target;
        }
        if (previous != null && !sameRuntime) {
            host.onRuntimeDeactivated(previous);
        }
        host.onRuntimeActivated(target, adapter);
        PermissionRequest pending = target.takePendingPermission();
        if (pending != null) {
            adapter.onPermissionRequested(pending);
        }
        host.onSessionListChanged();
        return target;
    }

    /**
     * Allocates and attaches a runtime that reuses the current provider/model and
     * permission mode. Returns {@code null} when the session cap is reached.
     */
    public SessionRuntime createAndAttach() {
        SessionRuntime runtime;
        synchronized (this) {
            if (disposed || runtimes.size() >= MAX_SESSIONS) {
                return null;
            }
            runtime = runtimeFactory.create(host.getProject(), active, host.getPermissionSessionId());
            if (runtime == null) {
                return null;
            }
            runtimes.put(runtime.getKey(), runtime);
        }
        return attach(runtime.getKey());
    }

    /**
     * Stops and removes a runtime. When it was the displayed one, an empty
     * replacement runtime is attached so the window always has a live session.
     *
     * @return {@code false} when the key is unknown
     */
    public boolean stop(String key) {
        SessionRuntime stopped;
        SessionRuntime replacement = null;
        boolean wasActive;
        synchronized (this) {
            if (disposed) {
                return false;
            }
            stopped = runtimes.get(key);
            if (stopped == null) {
                return false;
            }
            wasActive = stopped == active;
            runtimes.remove(key);
            stopped.setActive(false);
            SessionCallbackAdapter adapter = stopped.getUiAdapter();
            stopped.setUiAdapter(null);
            if (adapter != null) {
                adapter.deactivate();
                adapter.dispose();
            }
            if (wasActive) {
                active = null;
                replacement = runtimeFactory.create(host.getProject(), stopped, host.getPermissionSessionId());
                if (replacement != null) {
                    runtimes.put(replacement.getKey(), replacement);
                }
            }
        }
        stopped.dispose();
        if (replacement != null) {
            attach(replacement.getKey());
        }
        host.onSessionListChanged();
        return true;
    }

    /** Serializes the panel payload for {@code window.onAgentViewSessions}. */
    public synchronized String listJson(String requestId) {
        JsonObject response = new JsonObject();
        response.addProperty("requestId", requestId);
        response.addProperty("activeKey", active != null ? active.getKey() : null);
        JsonArray sessions = new JsonArray();
        for (SessionRuntime runtime : runtimes.values()) {
            sessions.add(runtime.toJsonRow());
        }
        response.add("sessions", sessions);
        return response.toString();
    }

    public synchronized SessionRuntime getActiveRuntime() {
        return active;
    }

    public synchronized boolean isActiveSession(ClaudeSession session) {
        return active != null && active.getSession() == session;
    }

    public synchronized int size() {
        return runtimes.size();
    }

    public synchronized boolean isDisposed() {
        return disposed;
    }

    /** Stops every runtime and releases owned bridges. Called from window dispose. */
    public void disposeAll() {
        List<SessionRuntime> snapshot;
        synchronized (this) {
            if (disposed) {
                return;
            }
            disposed = true;
            snapshot = new ArrayList<>(runtimes.values());
            runtimes.clear();
            active = null;
        }
        for (SessionRuntime runtime : snapshot) {
            runtime.dispose();
        }
    }

    private void attachAdapter(SessionRuntime runtime, SessionCallbackAdapter adapter) {
        runtime.setUiAdapter(adapter);
        runtime.setActive(true);
        runtime.markRead();
    }

    /**
     * Drops the window-bound runtime after a window-level session rebuild. The
     * rebuild already interrupted the old session; interrupting it again here could
     * abort the replacement if both share a provider channel.
     */
    private void retireActiveRuntime(SessionRuntime runtime) {
        runtime.release(false);
    }

    private static SessionRuntime createOwnedRuntime(Project project, SessionRuntime source, String permissionSessionId) {
        SessionBridgeBundle bundle = SessionBridgeBundle.createOwned(permissionSessionId);
        ClaudeSession session = bundle.newSession(project);
        if (source != null && source.getSession() != null) {
            ClaudeSession sourceSession = source.getSession();
            session.setProvider(sourceSession.getProvider());
            session.setModelVerbatim(sourceSession.getModel());
            session.setPermissionMode(sourceSession.getPermissionMode());
        }
        return new SessionRuntime(UUID.randomUUID().toString(), session, bundle, true);
    }
}
