package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AgentSessionRegistryTest {

    private static final class FixtureHost implements AgentSessionRegistry.Host {
        final StreamMessageCoalescer coalescer = new StreamMessageCoalescer(new StreamMessageCoalescer.JsCallbackTarget() {
            @Override public boolean callJavaScript(String name, String... args) { return true; }
            @Override public boolean isDisposed() { return false; }
            @Override public HandlerContext getHandlerContext() { return null; }
        });
        final List<String> activated = new ArrayList<>();
        final List<String> deactivated = new ArrayList<>();
        int listChanges;

        @Override public Project getProject() { return null; }
        @Override public String getPermissionSessionId() { return "permission-key"; }

        @Override
        public SessionCallbackAdapter createUiAdapter(SessionRuntime runtime) {
            return new SessionCallbackAdapter(coalescer, new SessionCallbackAdapter.JsTarget() {
                @Override public void callJavaScript(String name, String... args) { }
            }, null, () -> true, null);
        }

        @Override public void onRuntimeActivated(SessionRuntime runtime, SessionCallbackAdapter adapter) {
            activated.add(runtime.getKey());
        }

        @Override public void onRuntimeDeactivated(SessionRuntime runtime) {
            deactivated.add(runtime.getKey());
        }

        @Override public void onSessionListChanged() { listChanges++; }
        @Override public boolean isDisposed() { return false; }
    }

    private static final class RecordingFactory implements AgentSessionRegistry.RuntimeFactory {
        int created;

        @Override
        public SessionRuntime create(Project project, SessionRuntime source, String permissionSessionId) {
            created++;
            ClaudeSession session = new ClaudeSession(null, null, null, null) {
                @Override public CompletableFuture<Void> interrupt() { return CompletableFuture.completedFuture(null); }
            };
            return new SessionRuntime("runtime-" + created, session, null, true);
        }
    }

    private static ClaudeSession session() {
        return new ClaudeSession(null, null, null, null) {
            @Override public CompletableFuture<Void> interrupt() { return CompletableFuture.completedFuture(null); }
        };
    }

    @Test
    public void windowSessionBecomesTheActiveRuntime() {
        FixtureHost host = new FixtureHost();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, new RecordingFactory());
        try {
            SessionRuntime runtime = registerInitial(registry, host);
            assertSame(runtime, registry.getActiveRuntime());
            assertTrue(runtime.isActive());
            assertEquals(1, registry.size());

            JsonObject payload = JsonParser.parseString(registry.listJson("req-1")).getAsJsonObject();
            assertEquals("req-1", payload.get("requestId").getAsString());
            assertEquals(runtime.getKey(), payload.get("activeKey").getAsString());
            JsonArray sessions = payload.getAsJsonArray("sessions");
            assertEquals(1, sessions.size());
            assertTrue(sessions.get(0).getAsJsonObject().get("isActive").getAsBoolean());
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    @Test
    public void attachSwapsTheActiveRuntimeWithoutRemovingIt() {
        FixtureHost host = new FixtureHost();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, new RecordingFactory());
        try {
            SessionRuntime initial = registerInitial(registry, host);
            SessionRuntime created = registry.createAndAttach();
            assertNotNull(created);
            assertEquals(2, registry.size());
            assertSame(created, registry.getActiveRuntime());
            assertFalse(initial.isActive());
            assertTrue(created.isActive());
            assertEquals(List.of(created.getKey()), host.activated);
            assertTrue(host.deactivated.contains(initial.getKey()));

            SessionRuntime reattached = registry.attach(initial.getKey());
            assertSame(initial, reattached);
            assertSame(initial, registry.getActiveRuntime());
            assertTrue(initial.isActive());
            assertFalse(created.isActive());
            assertEquals(List.of(created.getKey(), initial.getKey()), host.activated);
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    @Test
    public void stoppingTheActiveRuntimeAttachesAnEmptyReplacement() {
        FixtureHost host = new FixtureHost();
        RecordingFactory factory = new RecordingFactory();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, factory);
        try {
            SessionRuntime initial = registerInitial(registry, host);
            assertTrue(registry.stop(initial.getKey()));

            SessionRuntime replacement = registry.getActiveRuntime();
            assertNotNull(replacement);
            assertNotSame(initial.getKey(), replacement.getKey());
            assertEquals(1, registry.size());
            assertEquals(1, factory.created);
            assertTrue(replacement.isActive());

            JsonObject payload = JsonParser.parseString(registry.listJson("req-2")).getAsJsonObject();
            assertEquals(replacement.getKey(), payload.get("activeKey").getAsString());
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    @Test
    public void stoppingAnInactiveRuntimeLeavesTheActiveOneBound() {
        FixtureHost host = new FixtureHost();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, new RecordingFactory());
        try {
            SessionRuntime initial = registerInitial(registry, host);
            SessionRuntime created = registry.createAndAttach();
            registry.attach(initial.getKey());

            assertTrue(registry.stop(created.getKey()));
            assertSame(initial, registry.getActiveRuntime());
            assertEquals(1, registry.size());
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    @Test
    public void sessionCapRejectsNewSessions() {
        FixtureHost host = new FixtureHost();
        RecordingFactory factory = new RecordingFactory();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, factory);
        try {
            registerInitial(registry, host);
            for (int i = 1; i < AgentSessionRegistry.MAX_SESSIONS; i++) {
                assertNotNull(registry.createAndAttach());
            }
            assertEquals(AgentSessionRegistry.MAX_SESSIONS, registry.size());
            assertNull(registry.createAndAttach());
            assertEquals(AgentSessionRegistry.MAX_SESSIONS - 1, factory.created);
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    @Test
    public void reattachingTheActiveRuntimeReplaysIt() {
        FixtureHost host = new FixtureHost();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, new RecordingFactory());
        try {
            SessionRuntime initial = registerInitial(registry, host);
            host.activated.clear();
            host.deactivated.clear();

            assertSame(initial, registry.attach(initial.getKey()));

            assertEquals(List.of(initial.getKey()), host.activated);
            assertTrue(host.deactivated.isEmpty());
            assertNotNull(initial.getUiAdapter());
            assertEquals(1, registry.size());
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    @Test
    public void unknownKeysAreIgnored() {
        FixtureHost host = new FixtureHost();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, new RecordingFactory());
        try {
            registerInitial(registry, host);
            assertNull(registry.attach("missing"));
            assertFalse(registry.stop("missing"));
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    @Test
    public void windowRebuildRetiresTheOldRowInsteadOfAccumulatingIt() {
        FixtureHost host = new FixtureHost();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, new RecordingFactory());
        try {
            SessionRuntime initial = registerInitial(registry, host);

            registry.registerWindowSession(session(), host.createUiAdapter(null));

            SessionRuntime rebuilt = registry.getActiveRuntime();
            assertNotNull(rebuilt);
            assertNotSame(initial.getKey(), rebuilt.getKey());
            assertEquals(1, registry.size());
            assertFalse(initial.isActive());
            assertNull(initial.getUiAdapter());
            assertNull(registry.attach(initial.getKey()));
            assertTrue(host.listChanges > 0);
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    @Test
    public void registeringTheSameSessionAgainKeepsTheExistingRow() {
        FixtureHost host = new FixtureHost();
        AgentSessionRegistry registry = new AgentSessionRegistry(host, new RecordingFactory());
        try {
            SessionRuntime initial = registerInitial(registry, host);
            ClaudeSession sameSession = initial.getSession();

            registry.registerWindowSession(sameSession, host.createUiAdapter(null));

            assertSame(initial, registry.getActiveRuntime());
            assertEquals(1, registry.size());
            assertNotNull(initial.getUiAdapter());
        } finally {
            registry.disposeAll();
            host.coalescer.dispose();
        }
    }

    private static SessionRuntime registerInitial(AgentSessionRegistry registry, FixtureHost host) {
        SessionCallbackAdapter adapter = host.createUiAdapter(null);
        registry.registerWindowSession(session(), adapter);
        return registry.getActiveRuntime();
    }
}
