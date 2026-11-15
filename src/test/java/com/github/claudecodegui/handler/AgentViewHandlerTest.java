package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.AgentSessionRegistry;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionCallbackAdapter;
import com.github.claudecodegui.session.SessionRuntime;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verifies the request/response contract of the Agent View message types. */
public class AgentViewHandlerTest {

    private Application previousApplication;

    @Before
    public void runEdtWorkImmediately() {
        previousApplication = ApplicationManager.getApplication();
        Application application = (Application) Proxy.newProxyInstance(Application.class.getClassLoader(),
                new Class<?>[]{Application.class}, (proxy, method, arguments) -> {
                    if ("invokeLater".equals(method.getName())) {
                        ((Runnable) arguments[0]).run();
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
        ApplicationManager.setApplication(application);
    }

    @After
    public void restoreApplication() {
        ApplicationManager.setApplication(previousApplication);
    }

    @Test
    public void listsSessionsWithTheRequestId() {
        Fixture fixture = new Fixture();
        try {
            fixture.handler.handle("agent_view_list", "{\"requestId\":\"req-1\"}");

            JsonObject payload = fixture.last("onAgentViewSessions");
            assertNotNull(payload);
            assertEquals("req-1", payload.get("requestId").getAsString());
            assertTrue(payload.has("activeKey"));
            assertEquals(1, payload.getAsJsonArray("sessions").size());
        } finally {
            fixture.dispose();
        }
    }

    @Test
    public void missingRegistryReportsUnavailable() {
        Fixture fixture = new Fixture(false);
        fixture.handler.handle("agent_view_list", "{\"requestId\":\"req-1\"}");

        JsonObject error = fixture.last("onAgentViewError");
        assertNotNull(error);
        assertEquals("unavailable", error.get("code").getAsString());
        assertEquals("req-1", error.get("requestId").getAsString());
    }

    @Test
    public void attachWithoutKeyIsRejectedBeforeDispatch() {
        Fixture fixture = new Fixture();
        try {
            fixture.handler.handle("agent_view_attach", "{\"requestId\":\"req-2\"}");

            JsonObject error = fixture.last("onAgentViewError");
            assertNotNull(error);
            assertEquals("invalid_request", error.get("code").getAsString());
        } finally {
            fixture.dispose();
        }
    }

    @Test
    public void attachToUnknownSessionReportsNotFound() {
        Fixture fixture = new Fixture();
        try {
            fixture.handler.handle("agent_view_attach", "{\"requestId\":\"req-3\",\"key\":\"missing\"}");

            JsonObject error = fixture.last("onAgentViewError");
            assertNotNull(error);
            assertEquals("not_found", error.get("code").getAsString());
            assertEquals("missing", error.get("key").getAsString());
        } finally {
            fixture.dispose();
        }
    }

    @Test
    public void newSessionAcknowledgesTheAttachedKey() {
        Fixture fixture = new Fixture();
        try {
            fixture.handler.handle("agent_view_new", "{\"requestId\":\"req-4\"}");

            JsonObject ack = fixture.last("onAgentViewAttached");
            assertNotNull(ack);
            assertEquals("req-4", ack.get("requestId").getAsString());
            assertTrue(ack.get("ok").getAsBoolean());
            assertNotNull(ack.get("key").getAsString());
            assertEquals(2, fixture.registry.size());
        } finally {
            fixture.dispose();
        }
    }

    @Test
    public void newSessionAtTheCapReportsLimit() {
        Fixture fixture = new Fixture();
        try {
            while (fixture.registry.size() < AgentSessionRegistry.MAX_SESSIONS) {
                fixture.registry.createAndAttach();
            }
            fixture.handler.handle("agent_view_new", "{\"requestId\":\"req-5\"}");

            JsonObject error = fixture.last("onAgentViewError");
            assertNotNull(error);
            assertEquals("limit", error.get("code").getAsString());
        } finally {
            fixture.dispose();
        }
    }

    @Test
    public void stoppingAnInactiveSessionReportsWasActiveFalse() {
        Fixture fixture = new Fixture();
        try {
            SessionRuntime initial = fixture.registry.getActiveRuntime();
            SessionRuntime created = fixture.registry.createAndAttach();
            fixture.registry.attach(initial.getKey());

            fixture.handler.handle("agent_view_stop",
                    "{\"requestId\":\"req-6\",\"key\":\"" + created.getKey() + "\"}");

            JsonObject ack = fixture.last("onAgentViewStopped");
            assertNotNull(ack);
            assertEquals(created.getKey(), ack.get("key").getAsString());
            assertTrue(ack.get("ok").getAsBoolean());
            assertFalse(ack.get("wasActive").getAsBoolean());
        } finally {
            fixture.dispose();
        }
    }

    @Test
    public void unsupportedTypesAreNotClaimed() {
        Fixture fixture = new Fixture();
        try {
            assertFalse(fixture.handler.handle("agent_view_bogus", "{}"));
            List<String> types = List.of(fixture.handler.getSupportedTypes());
            assertEquals(List.of("agent_view_list", "agent_view_attach", "agent_view_stop", "agent_view_new"), types);
        } finally {
            fixture.dispose();
        }
    }

    /** Registry + handler wired to a recording JS channel. */
    private static final class Fixture implements AgentSessionRegistry.Host {
        final AgentSessionRegistry registry;
        final AgentViewHandler handler;
        final List<String> calls = new ArrayList<>();
        final List<JsonObject> payloads = new ArrayList<>();

        Fixture() {
            this(true);
        }

        Fixture(boolean withRegistry) {
            registry = withRegistry ? new AgentSessionRegistry(this, RecordingFactory.INSTANCE) : null;
            HandlerContext context = new HandlerContext(null, null, null, null,
                    new HandlerContext.JsCallback() {
                        @Override
                        public void callJavaScript(String functionName, String... args) {
                            if (args != null && args.length > 0) {
                                calls.add(functionName);
                                payloads.add(JsonParser.parseString(args[0]).getAsJsonObject());
                            }
                        }

                        @Override
                        public String escapeJs(String str) {
                            return str;
                        }
                    });
            handler = new AgentViewHandler(context, registry);
            if (registry != null) {
                registry.registerWindowSession(session(), adapter());
            }
        }

        /** Resolves the most recent payload delivered to {@code functionName}. */
        JsonObject last(String functionName) {
            for (int i = calls.size() - 1; i >= 0; i--) {
                if (calls.get(i).equals(functionName)) {
                    return payloads.get(i);
                }
            }
            return null;
        }

        @Override public Project getProject() { return null; }
        @Override public String getPermissionSessionId() { return "permission-key"; }

        @Override
        public SessionCallbackAdapter createUiAdapter(SessionRuntime runtime) {
            return adapter();
        }

        @Override public void onRuntimeActivated(SessionRuntime runtime, SessionCallbackAdapter adapter) { }
        @Override public void onRuntimeDeactivated(SessionRuntime runtime) { }
        @Override public void onSessionListChanged() { }
        @Override public boolean isDisposed() { return false; }

        void dispose() {
            if (registry != null) {
                registry.disposeAll();
            }
        }

        private static SessionCallbackAdapter adapter() {
            return new SessionCallbackAdapter(null,
                    (name, args) -> { }, null, () -> true, null);
        }

        private static ClaudeSession session() {
            return new ClaudeSession(null, null, null, null) {
                @Override public CompletableFuture<Void> interrupt() { return CompletableFuture.completedFuture(null); }
            };
        }
    }

    /** Avoids building real bridges; each new runtime is a bare session. */
    private enum RecordingFactory implements AgentSessionRegistry.RuntimeFactory {
        INSTANCE;

        private final java.util.concurrent.atomic.AtomicInteger created =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public SessionRuntime create(Project project, SessionRuntime source, String permissionSessionId) {
            return new SessionRuntime("runtime-" + created.incrementAndGet(), Fixture.session(), null, true);
        }
    }
}
