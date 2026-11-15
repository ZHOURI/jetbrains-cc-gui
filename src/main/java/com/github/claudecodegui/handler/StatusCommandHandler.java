package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.cli.CliStatusDetector;
import com.github.claudecodegui.cli.CliToolStatus;
import com.github.claudecodegui.dependency.DependencyManager;
import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.util.PluginMetadata;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Backend data for GUI-local slash commands such as {@code /status},
 * {@code /doctor}, {@code /memory} and {@code /hooks}.
 *
 * <p>Messages:
 * <ul>
 *   <li>{@code get_status_info:{"requestId":"...","kind":"status|doctor|memory|hooks","provider":"..."}}
 *       — pushes {@code window.onStatusInfo}</li>
 * </ul>
 */
public class StatusCommandHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(StatusCommandHandler.class);
    private static final Gson GSON = new Gson();

    private static final String[] SUPPORTED_TYPES = {
            "get_status_info",
    };

    public StatusCommandHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean handle(String type, String content) {
        if (!"get_status_info".equals(type)) {
            return false;
        }
        handleGetStatusInfo(content);
        return true;
    }

    private void handleGetStatusInfo(String content) {
        String requestId = null;
        String kind = "status";
        String provider = null;
        try {
            JsonObject request = GSON.fromJson(content, JsonObject.class);
            if (request != null) {
                requestId = getString(request, "requestId");
                kind = getString(request, "kind");
                provider = getString(request, "provider");
            }
        } catch (Exception e) {
            LOG.warn("[StatusCommandHandler] Invalid request payload: " + e.getMessage());
        }

        final String finalRequestId = requestId;
        final String finalKind = kind == null ? "status" : kind;
        final String finalProvider = provider;

        CompletableFuture.runAsync(() -> {
            JsonObject payload = buildPayload(finalRequestId, finalKind, finalProvider);
            String json = GSON.toJson(payload);
            ApplicationManager.getApplication().invokeLater(() ->
                    callJavaScript("window.onStatusInfo", escapeJs(json))
            );
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[StatusCommandHandler] Failed to build status payload: " + ex.getMessage(), ex);
            JsonObject error = new JsonObject();
            if (finalRequestId != null) {
                error.addProperty("requestId", finalRequestId);
            }
            error.addProperty("kind", finalKind);
            error.addProperty("error", ex.getMessage() != null ? ex.getMessage() : "unknown error");
            String json = GSON.toJson(error);
            ApplicationManager.getApplication().invokeLater(() ->
                    callJavaScript("window.onStatusInfo", escapeJs(json))
            );
            return null;
        });
    }

    private JsonObject buildPayload(String requestId, String kind, String requestedProvider) {
        JsonObject payload = new JsonObject();
        if (requestId != null) {
            payload.addProperty("requestId", requestId);
        }
        payload.addProperty("kind", kind);
        payload.addProperty("generatedAt", System.currentTimeMillis());

        String provider = requestedProvider;
        if (provider == null || provider.isBlank()) {
            provider = context.getCurrentProvider();
        }
        if (provider == null || provider.isBlank()) {
            provider = "claude";
        }
        boolean isCodex = "codex".equalsIgnoreCase(provider);
        payload.addProperty("provider", provider);

        String home = nullToEmpty(NodeDetector.resolveHomeForFileOps());
        String cwd = resolveCwd();
        String nodePath = NodeDetector.getInstance().getCachedNodePath();
        String nodeVersion = NodeDetector.getInstance().getCachedNodeVersion();

        DependencyManager dependencyManager = new DependencyManager();
        JsonObject sdkStatus = dependencyManager.getAllSdkStatus();

        JsonObject status = new JsonObject();
        status.addProperty("pluginVersion", PluginMetadata.getPluginVersion());
        status.addProperty("ideVersion", resolveIdeVersion());
        status.addProperty("nodePath", nodePath != null ? nodePath : "");
        status.addProperty("nodeVersion", nodeVersion != null ? nodeVersion : "");
        status.addProperty("provider", provider);
        status.addProperty("model", nullToEmpty(context.getCurrentModel()));
        status.addProperty("permissionMode", resolvePermissionMode());
        status.addProperty("sessionId", resolveSessionId());
        status.addProperty("cwd", cwd);
        status.addProperty("javaVersion", System.getProperty("java.version", ""));
        status.addProperty("os", buildOsLabel());
        addActiveSdk(status, sdkStatus, isCodex);
        payload.add("status", status);

        if ("doctor".equals(kind)) {
            JsonObject diagnostics = new JsonObject();
            diagnostics.addProperty("nodeAvailable", dependencyManager.checkNodeEnvironment());
            diagnostics.add("sdks", sdkStatus);
            diagnostics.add("clis", buildCliStatus());
            payload.add("diagnostics", diagnostics);
        }

        if ("memory".equals(kind)) {
            payload.add("memoryFiles", buildMemoryFiles(home, cwd, isCodex));
        }
        if ("hooks".equals(kind)) {
            payload.add("hookFiles", buildHookFiles(home, cwd, isCodex));
        }

        return payload;
    }

    private String resolveCwd() {
        ClaudeSession session = context.getSession();
        if (session != null) {
            String cwd = session.getCwd();
            if (cwd != null && !cwd.isBlank()) {
                return cwd;
            }
        }
        String effective = context.resolveEffectiveWorkingDirectory();
        return effective != null ? effective : "";
    }

    private String resolvePermissionMode() {
        ClaudeSession session = context.getSession();
        String mode = session != null ? session.getPermissionMode() : null;
        return mode != null ? mode : "";
    }

    private String resolveSessionId() {
        ClaudeSession session = context.getSession();
        String sessionId = session != null ? session.getSessionId() : null;
        return sessionId != null ? sessionId : "";
    }

    private static String resolveIdeVersion() {
        try {
            ApplicationInfo info = ApplicationInfo.getInstance();
            String version = info != null ? info.getFullVersion() : null;
            return version != null ? version : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String buildOsLabel() {
        String name = System.getProperty("os.name", "");
        String version = System.getProperty("os.version", "");
        String arch = System.getProperty("os.arch", "");
        return (name + " " + version + " (" + arch + ")").trim();
    }

    private static void addActiveSdk(JsonObject status, JsonObject sdkStatus, boolean isCodex) {
        String sdkId = isCodex ? "codex-sdk" : "claude-sdk";
        JsonElement element = sdkStatus.get(sdkId);
        if (element == null || !element.isJsonObject()) {
            status.addProperty("sdkId", sdkId);
            status.addProperty("sdkInstalled", false);
            return;
        }
        JsonObject sdk = element.getAsJsonObject();
        status.addProperty("sdkId", sdkId);
        status.addProperty("sdkInstalled", getBoolean(sdk, "installed"));
        status.addProperty("sdkVersion", getStringOrEmpty(sdk, "version"));
        status.addProperty("sdkMinimumVersion", getStringOrEmpty(sdk, "minimumVersion"));
        if (sdk.has("meetsMinimumVersion") && !sdk.get("meetsMinimumVersion").isJsonNull()) {
            status.addProperty("sdkMeetsMinimum", sdk.get("meetsMinimumVersion").getAsBoolean());
        }
    }

    private static JsonArray buildCliStatus() {
        JsonArray array = new JsonArray();
        try {
            Map<String, CliToolStatus> statuses = CliStatusDetector.detectAll();
            for (CliToolStatus tool : statuses.values()) {
                JsonObject item = new JsonObject();
                item.addProperty("id", tool.getId());
                item.addProperty("name", tool.getName());
                item.addProperty("installed", tool.isInstalled());
                if (tool.getVersion() != null) {
                    item.addProperty("version", tool.getVersion());
                }
                if (tool.getPath() != null) {
                    item.addProperty("path", tool.getPath());
                }
                if (tool.getError() != null) {
                    item.addProperty("error", tool.getError());
                }
                array.add(item);
            }
        } catch (Exception e) {
            LOG.warn("[StatusCommandHandler] CLI detection failed: " + e.getMessage());
        }
        return array;
    }

    static JsonArray buildMemoryFiles(String home, String cwd, boolean isCodex) {
        JsonArray array = new JsonArray();
        if (isCodex) {
            if (!home.isEmpty()) {
                array.add(fileEntry("codexUserMemory", Paths.get(home, ".codex", "AGENTS.md")));
            }
            if (cwd != null && !cwd.isEmpty()) {
                array.add(fileEntry("codexProjectMemory", Paths.get(cwd, "AGENTS.md")));
            }
            return array;
        }
        if (!home.isEmpty()) {
            array.add(fileEntry("userMemory", Paths.get(home, ".claude", "CLAUDE.md")));
        }
        if (cwd != null && !cwd.isEmpty()) {
            array.add(fileEntry("projectMemory", Paths.get(cwd, "CLAUDE.md")));
            array.add(fileEntry("projectLocalMemory", Paths.get(cwd, "CLAUDE.local.md")));
        }
        return array;
    }

    static JsonArray buildHookFiles(String home, String cwd, boolean isCodex) {
        JsonArray array = new JsonArray();
        if (isCodex) {
            if (!home.isEmpty()) {
                array.add(fileEntry("codexConfig", Paths.get(home, ".codex", "config.toml")));
            }
            return array;
        }
        if (!home.isEmpty()) {
            array.add(hookFileEntry("userSettings", Paths.get(home, ".claude", "settings.json")));
        }
        if (cwd != null && !cwd.isEmpty()) {
            array.add(hookFileEntry("projectSettings", Paths.get(cwd, ".claude", "settings.json")));
            array.add(hookFileEntry("projectLocalSettings", Paths.get(cwd, ".claude", "settings.local.json")));
        }
        return array;
    }

    private static JsonObject fileEntry(String id, Path path) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("path", path.toString());
        entry.addProperty("exists", Files.isRegularFile(path));
        return entry;
    }

    private static JsonObject hookFileEntry(String id, Path path) {
        JsonObject entry = fileEntry(id, path);
        entry.addProperty("hasHooks", containsHooks(path));
        return entry;
    }

    private static boolean containsHooks(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonElement hooks = root.getAsJsonObject().get("hooks");
            return hooks != null && hooks.isJsonObject() && hooks.getAsJsonObject().size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String getStringOrEmpty(JsonObject obj, String key) {
        String value = getString(obj, key);
        return value != null ? value : "";
    }

    private static boolean getBoolean(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return false;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
