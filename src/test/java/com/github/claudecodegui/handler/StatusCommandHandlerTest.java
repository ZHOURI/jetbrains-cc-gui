package com.github.claudecodegui.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatusCommandHandlerTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void claudeMemoryFilesReportExistence() throws Exception {
        Path home = temporary.newFolder("home").toPath();
        Path cwd = temporary.newFolder("project").toPath();
        Path userMemory = Files.createDirectories(home.resolve(".claude")).resolve("CLAUDE.md");
        Files.writeString(userMemory, "# User memory\n");

        JsonArray files = StatusCommandHandler.buildMemoryFiles(home.toString(), cwd.toString(), false);

        assertEquals(3, files.size());
        JsonObject user = entry(files, "userMemory");
        assertEquals(userMemory.toString(), user.get("path").getAsString());
        assertTrue(user.get("exists").getAsBoolean());
        assertFalse(entry(files, "projectMemory").get("exists").getAsBoolean());
        assertFalse(entry(files, "projectLocalMemory").get("exists").getAsBoolean());
    }

    @Test public void codexMemoryFilesUseAgentsMarkdown() throws Exception {
        Path home = temporary.newFolder("home").toPath();
        Path cwd = temporary.newFolder("project").toPath();
        Path projectMemory = cwd.resolve("AGENTS.md");
        Files.writeString(projectMemory, "# Agents\n");

        JsonArray files = StatusCommandHandler.buildMemoryFiles(home.toString(), cwd.toString(), true);

        assertEquals(2, files.size());
        assertEquals("codexUserMemory", files.get(0).getAsJsonObject().get("id").getAsString());
        JsonObject project = entry(files, "codexProjectMemory");
        assertEquals(projectMemory.toString(), project.get("path").getAsString());
        assertTrue(project.get("exists").getAsBoolean());
    }

    @Test public void claudeHookFilesDetectConfiguredHooks() throws Exception {
        Path home = temporary.newFolder("home").toPath();
        Path cwd = temporary.newFolder("project").toPath();
        Path projectSettings = Files.createDirectories(cwd.resolve(".claude")).resolve("settings.json");
        Files.writeString(projectSettings, "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[]}]}}");

        JsonArray files = StatusCommandHandler.buildHookFiles(home.toString(), cwd.toString(), false);

        assertEquals(3, files.size());
        assertFalse(entry(files, "userSettings").get("exists").getAsBoolean());
        JsonObject project = entry(files, "projectSettings");
        assertTrue(project.get("exists").getAsBoolean());
        assertTrue(project.get("hasHooks").getAsBoolean());
    }

    @Test public void hookFileWithoutHooksSectionIsNotFlagged() throws Exception {
        Path home = temporary.newFolder("home").toPath();
        Path cwd = temporary.newFolder("project").toPath();
        Path settings = Files.createDirectories(home.resolve(".claude")).resolve("settings.json");
        Files.writeString(settings, "{\"permissions\":{\"allow\":[]}}");

        JsonObject user = entry(StatusCommandHandler.buildHookFiles(
                home.toString(), cwd.toString(), false), "userSettings");

        assertTrue(user.get("exists").getAsBoolean());
        assertFalse(user.get("hasHooks").getAsBoolean());
    }

    @Test public void codexHooksPointAtConfigToml() throws Exception {
        Path home = temporary.newFolder("home").toPath();
        Path cwd = temporary.newFolder("project").toPath();
        Path config = Files.createDirectories(home.resolve(".codex")).resolve("config.toml");
        Files.writeString(config, "[mcp_servers]\n");

        JsonArray files = StatusCommandHandler.buildHookFiles(home.toString(), cwd.toString(), true);

        assertEquals(1, files.size());
        JsonObject entry = files.get(0).getAsJsonObject();
        assertEquals("codexConfig", entry.get("id").getAsString());
        assertEquals(config.toString(), entry.get("path").getAsString());
        assertTrue(entry.get("exists").getAsBoolean());
    }

    private static JsonObject entry(JsonArray files, String id) {
        for (int i = 0; i < files.size(); i++) {
            JsonObject entry = files.get(i).getAsJsonObject();
            if (id.equals(entry.get("id").getAsString())) {
                return entry;
            }
        }
        throw new AssertionError("Missing entry: " + id);
    }
}
