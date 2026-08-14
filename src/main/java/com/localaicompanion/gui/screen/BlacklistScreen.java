package com.localaicompanion.gui.screen;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.PermissionConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * 方块黑名单管理界面
 */
public class BlacklistScreen extends Screen {
    private final Screen parent;
    private final PermissionConfig permissionConfig;

    private TextFieldWidget addField;
    private int scrollOffset = 0;
    private static final int ITEMS_PER_PAGE = 10;

    public BlacklistScreen(Screen parent, PermissionConfig permissionConfig) {
        super(Text.literal("方块黑名单管理"));
        this.parent = parent;
        this.permissionConfig = permissionConfig;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 20;
        int listWidth = 280;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 80, y, 160, 20,
            Text.literal("方块黑名单管理").formatted(Formatting.BOLD, Formatting.RED),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 35;

        // 添加输入框
        addField = new TextFieldWidget(
            this.textRenderer,
            centerX - listWidth / 2, y,
            listWidth - 80, 20,
            Text.literal("方块ID")
        );
        addField.setMaxLength(100);
        this.addDrawableChild(addField);

        // 添加按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("添加"),
            button -> addBlock()
        ).dimensions(centerX + listWidth / 2 - 75, y, 75, 20).build());
        y += 30;

        // 列表标题
        TextWidget listTitle = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("当前黑名单（共 " + permissionConfig.blockBlacklist.size() + " 个）：").formatted(Formatting.GRAY),
            this.textRenderer
        );
        this.addDrawableChild(listTitle);
        y += 20;

        // 显示黑名单列表
        List<String> blacklist = permissionConfig.blockBlacklist;
        int startIndex = scrollOffset;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, blacklist.size());

        for (int i = startIndex; i < endIndex; i++) {
            final int index = i;
            String blockId = blacklist.get(i);

            // 显示方块ID
            TextWidget blockLabel = new TextWidget(
                centerX - listWidth / 2, y, listWidth - 60, 18,
                Text.literal(blockId),
                this.textRenderer
            );
            this.addDrawableChild(blockLabel);

            // 删除按钮
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§c删除"),
                button -> removeBlock(index)
            ).dimensions(centerX + listWidth / 2 - 50, y - 1, 50, 18).build());

            y += 20;
        }

        y += 10;

        // 翻页按钮
        if (scrollOffset > 0) {
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("上一页"),
                button -> { scrollOffset -= ITEMS_PER_PAGE; clearAndInit(); }
            ).dimensions(centerX - 100, y, 80, 20).build());
        }

        if (endIndex < blacklist.size()) {
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("下一页"),
                button -> { scrollOffset += ITEMS_PER_PAGE; clearAndInit(); }
            ).dimensions(centerX + 20, y, 80, 20).build());
        }

        y += 35;

        // 返回按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("返回"),
            button -> {
                saveConfig();
                if (this.client != null) {
                    this.client.setScreen(parent);
                }
            }
        ).dimensions(centerX - 50, y, 100, 20).build());
    }

    private void addBlock() {
        String blockId = addField.getText().trim();
        if (!blockId.isEmpty()) {
            if (!blockId.contains(":")) {
                blockId = "minecraft:" + blockId;
            }
            permissionConfig.addToBlacklist(blockId);
            addField.setText("");
            saveConfig();
            clearAndInit();
        }
    }

    private void removeBlock(int index) {
        if (index >= 0 && index < permissionConfig.blockBlacklist.size()) {
            permissionConfig.blockBlacklist.remove(index);
            saveConfig();
            clearAndInit();
        }
    }

    private void saveConfig() {
        try {
            LocalAICompanion.getInstance().getConfigManager().saveAll();
        } catch (Exception e) {
            // 忽略保存错误
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
