package com.alibaba.arthas.idea.workbench.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.alibaba.arthas.idea.workbench.ArthasWorkbenchBundle;
import com.alibaba.arthas.idea.workbench.model.GatewayAuthMode;
import com.alibaba.arthas.idea.workbench.model.McpPasswordMode;
import com.alibaba.arthas.idea.workbench.model.PackageSourceType;
import com.alibaba.arthas.idea.workbench.model.PortAllocationMode;
import com.alibaba.arthas.idea.workbench.service.ArthasWorkbenchSettingsService;
import com.alibaba.arthas.idea.workbench.service.JifaWebRuntimeService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * {@link ArthasWorkbenchSettingsPanel} 的表单读写测试。
 */
public class ArthasWorkbenchSettingsPanelTest {

    @Test
    /**
     * 验证设置页可以正确回填状态、识别修改，并输出新的持久化状态。
     */
    public void shouldRoundTripStateAndDetectModification() {
        ArthasWorkbenchSettingsService.SettingsState state = new ArthasWorkbenchSettingsService.SettingsState();
        state.sourceType = PackageSourceType.OFFICIAL_VERSION.name();
        state.sourceValue = "4.1.8";
        state.portAllocationMode = PortAllocationMode.PREFER_CONFIGURED.name();
        state.httpPort = "8563";
        state.telnetPort = "3658";
        state.mcpGatewayPort = "18765";
        state.mcpGatewayAuthMode = GatewayAuthMode.FIXED.name();
        state.mcpGatewayToken = "gateway-token";
        state.mcpPasswordMode = McpPasswordMode.FIXED.name();
        state.mcpPassword = "secret";
        state.jifaHelperPath = "/tmp/jifa-helper";
        state.autoOpenTerminal = true;
        state.autoOpenWebUi = false;

        ArthasWorkbenchSettingsPanel panel = new ArthasWorkbenchSettingsPanel(null);
        panel.resetFrom(state);

        assertFalse(panel.isModified(state));
        assertEquals("/tmp/jifa-helper", panel.getJifaHelperPath());

        panel.setSourceType(PackageSourceType.LOCAL_PATH);
        panel.setSourceValue("/tmp/arthas");
        panel.setPortAllocationMode(PortAllocationMode.STRICT_CONFIGURED);
        panel.setHttpPort("9999");
        panel.setTelnetPort("7777");
        panel.setMcpGatewayPort("19527");
        panel.setGatewayAuthMode(GatewayAuthMode.DISABLED);
        panel.setMcpGatewayToken("changed-gateway-token");
        panel.setMcpPasswordMode(McpPasswordMode.DISABLED);
        panel.setMcpPassword("changed");
        panel.setJifaHelperPath("/opt/jifa-helper-dir");
        panel.setAutoOpenTerminal(false);
        panel.setAutoOpenWebUi(true);

        assertTrue(panel.isModified(state));

        ArthasWorkbenchSettingsService.SettingsState updated = panel.toState();
        assertEquals(PackageSourceType.LOCAL_PATH.name(), updated.sourceType);
        assertEquals("/tmp/arthas", updated.sourceValue);
        assertEquals(PortAllocationMode.STRICT_CONFIGURED.name(), updated.portAllocationMode);
        assertEquals("9999", updated.httpPort);
        assertEquals("7777", updated.telnetPort);
        assertEquals("19527", updated.mcpGatewayPort);
        assertEquals(GatewayAuthMode.DISABLED.name(), updated.mcpGatewayAuthMode);
        assertEquals("changed-gateway-token", updated.mcpGatewayToken);
        assertEquals(McpPasswordMode.DISABLED.name(), updated.mcpPasswordMode);
        assertEquals("changed", updated.mcpPassword);
        assertEquals("/opt/jifa-helper-dir", updated.jifaHelperPath);
        assertFalse(updated.autoOpenTerminal);
        assertTrue(updated.autoOpenWebUi);
    }

    @Test
    /**
     * 验证包来源切换后，“来源值”会切换成对应的交互模式与提示文本。
     */
    public void shouldSwitchSourceValueInteractionByPackageSourceType() {
        ArthasWorkbenchSettingsPanel panel = new ArthasWorkbenchSettingsPanel(null);

        ArthasWorkbenchSettingsService.SettingsState state = new ArthasWorkbenchSettingsService.SettingsState();
        state.sourceType = PackageSourceType.OFFICIAL_LATEST.name();
        state.sourceValue = "";
        panel.resetFrom(state);

        assertFalse(panel.isSourceValueEditable());
        assertFalse(panel.isSourceValueFocusable());
        assertTrue(panel.isUpdateOfficialLatestPackageButtonVisible());
        assertEquals(ArthasWorkbenchSettingsPanel.OFFICIAL_LATEST_DOWNLOAD_URL, panel.getDisplayedSourceValue());

        panel.setSourceType(PackageSourceType.OFFICIAL_VERSION);
        assertTrue(panel.isSourceValueEditable());
        assertTrue(panel.isSourceValueFocusable());
        assertFalse(panel.isUpdateOfficialLatestPackageButtonVisible());
        assertEquals(ArthasWorkbenchSettingsPanel.OFFICIAL_VERSION_PLACEHOLDER, panel.getSourceValuePlaceholder());

        panel.setSourceType(PackageSourceType.CUSTOM_REMOTE_ZIP);
        assertTrue(panel.isSourceValueEditable());
        assertTrue(panel.isSourceValueFocusable());
        assertFalse(panel.isUpdateOfficialLatestPackageButtonVisible());
        assertEquals(ArthasWorkbenchSettingsPanel.CUSTOM_REMOTE_ZIP_PLACEHOLDER, panel.getSourceValuePlaceholder());

        panel.setSourceType(PackageSourceType.LOCAL_ZIP);
        assertFalse(panel.isSourceValueEditable());
        assertFalse(panel.isSourceValueFocusable());
        assertFalse(panel.isUpdateOfficialLatestPackageButtonVisible());
        assertEquals(
                ArthasWorkbenchBundle.message("settings.source.placeholder.local_zip"),
                panel.getSourceValuePlaceholder());
        assertEquals(ArthasWorkbenchBundle.message("settings.source.tooltip.local_zip"), panel.getSourceValueTooltip());

        panel.setSourceType(PackageSourceType.LOCAL_PATH);
        assertFalse(panel.isSourceValueEditable());
        assertFalse(panel.isSourceValueFocusable());
        assertFalse(panel.isUpdateOfficialLatestPackageButtonVisible());
        assertEquals(
                ArthasWorkbenchBundle.message("settings.source.placeholder.local_path"),
                panel.getSourceValuePlaceholder());
        assertEquals(
                ArthasWorkbenchBundle.message("settings.source.tooltip.local_path"), panel.getSourceValueTooltip());
    }

    @Test
    /**
     * 验证 Arthas agent 密码与 Gateway 认证在不同模式下会正确切换输入框启用状态。
     */
    public void shouldTogglePasswordInputsByMode() {
        ArthasWorkbenchSettingsPanel panel = new ArthasWorkbenchSettingsPanel(null);

        panel.setMcpPasswordMode(McpPasswordMode.RANDOM);
        assertFalse(panel.isMcpPasswordEditable());

        panel.setMcpPasswordMode(McpPasswordMode.FIXED);
        assertTrue(panel.isMcpPasswordEditable());

        panel.setGatewayAuthMode(GatewayAuthMode.RANDOM);
        assertFalse(panel.isGatewayTokenEditable());
        assertTrue(panel.isGatewayRandomButtonEnabled());

        panel.setGatewayAuthMode(GatewayAuthMode.FIXED);
        assertTrue(panel.isGatewayTokenEditable());
        assertFalse(panel.isGatewayRandomButtonEnabled());

        panel.setGatewayAuthMode(GatewayAuthMode.DISABLED);
        assertFalse(panel.isGatewayTokenEditable());
        assertFalse(panel.isGatewayRandomButtonEnabled());
    }

    @Test
    /**
     * 验证设置页复制出来的是固定 Gateway MCP 配置，而不是仅供阅读的网关信息摘要。
     */
    public void shouldBuildGatewayMcpConfigSnippet() {
        String configWithToken =
                ArthasWorkbenchSettingsPanel.buildGatewayMcpConfig("http://127.0.0.1:18765/gateway", "gateway-token");

        assertTrue(configWithToken.contains("\"idea-arthas-workbench\""));
        assertTrue(configWithToken.contains("\"url\": \"http://127.0.0.1:18765/gateway/mcp\""));
        assertTrue(configWithToken.contains("\"Authorization\": \"Bearer gateway-token\""));

        String configWithoutToken =
                ArthasWorkbenchSettingsPanel.buildGatewayMcpConfig("http://127.0.0.1:18765/gateway", "");
        assertTrue(configWithoutToken.contains("\"url\": \"http://127.0.0.1:18765/gateway/mcp\""));
        assertFalse(configWithoutToken.contains("\"Authorization\""));
    }

    @Test
    public void shouldRenderAndRefreshJifaCacheSummary() throws Exception {
        Path root = Files.createTempDirectory("arthas-workbench-jifa-cache-panel");
        JifaWebRuntimeService.CacheSummary initialSummary = cacheSummary(root, 5L, 1024L, 2, true, 18489);
        JifaWebRuntimeService.CacheSummary refreshedSummary = cacheSummary(root, 1L, 128L, 0, false, -1);

        ArthasWorkbenchSettingsPanel.JifaCacheController controller =
                new ArthasWorkbenchSettingsPanel.JifaCacheController() {
                    private JifaWebRuntimeService.CacheSummary current = initialSummary;

                    @Override
                    public Path rootDirectory() {
                        return root;
                    }

                    @Override
                    public JifaWebRuntimeService.CacheSummary loadSummary() {
                        return current;
                    }

                    @Override
                    public JifaWebRuntimeService.CacheSummary clearLogs() {
                        return current;
                    }

                    @Override
                    public JifaWebRuntimeService.CacheSummary clearMetadata() {
                        current = refreshedSummary;
                        return current;
                    }

                    @Override
                    public JifaWebRuntimeService.CacheSummary clearAll() {
                        current = refreshedSummary;
                        return current;
                    }
                };

        ArthasWorkbenchSettingsPanel panel = new ArthasWorkbenchSettingsPanel(null, controller);

        assertEquals("", panel.getJifaHelperPath());
        assertEquals(root.toString(), panel.getJifaCacheRoot());
        assertTrue(panel.getJifaCacheOverviewText().contains("5 files"));
        assertTrue(panel.getJifaCacheMetadataText().contains("Imported mappings: 2"));
        String initialServerText = panel.getJifaCacheServerText();
        assertFalse(initialServerText.isBlank());

        panel.clickClearJifaMetadataButton();

        assertTrue(panel.getJifaCacheOverviewText().contains("1 files"));
        assertTrue(panel.getJifaCacheLogsText().contains("1 files"));
        assertTrue(panel.getJifaCacheMetadataText().contains("Imported mappings: 0"));
        assertFalse(panel.getJifaCacheServerText().isBlank());
        assertFalse(panel.getJifaCacheServerText().equals(initialServerText));
        assertFalse(panel.getJifaCacheStatusText().isBlank());
    }

    private JifaWebRuntimeService.CacheSummary cacheSummary(
            Path root, long totalFiles, long totalBytes, int importedEntries, boolean running, int port) {
        JifaWebRuntimeService.DirectorySummary storage =
                new JifaWebRuntimeService.DirectorySummary(root.resolve("storage"), totalBytes / 2, 2L, 0L);
        JifaWebRuntimeService.DirectorySummary metadata =
                new JifaWebRuntimeService.DirectorySummary(root.resolve("meta"), totalBytes / 4, 1L, 0L);
        JifaWebRuntimeService.DirectorySummary logs =
                new JifaWebRuntimeService.DirectorySummary(root.resolve("logs"), totalBytes / 8, 1L, 0L);
        JifaWebRuntimeService.DirectorySummary runtime =
                new JifaWebRuntimeService.DirectorySummary(root.resolve("runtime"), totalBytes / 8, 1L, 0L);
        return new JifaWebRuntimeService.CacheSummary(
                root, storage, metadata, logs, runtime, totalBytes, totalFiles, importedEntries, running, port, 1L);
    }
}
