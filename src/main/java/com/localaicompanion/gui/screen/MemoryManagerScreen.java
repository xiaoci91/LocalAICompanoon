package com.localaicompanion.gui.screen;

import com.localaicompanion.memory.MemoryManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * 记忆管理页面
 *
 * 功能：
 * - 长期记忆列表
 * - 新增、编辑、删除记忆条目
 * - 按分类筛选
 */
public class MemoryManagerScreen extends Screen {
    private final Screen parent;
    private final MemoryManager memoryManager;

    private List<MemoryManager.LongTermMemoryEntry> memoryList;
    private int scrollOffset = 0;
    private static final int VISIBLE_ENTRIES = 8;

    public MemoryManagerScreen(Screen parent, MemoryManager memoryManager) {
        super(Text.literal("记忆管理"));
        this.parent = parent;
        this.memoryManager = memoryManager;
        this.memoryList = memoryManager.getAllLongTermMemory();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 20;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 50, y, 100, 20,
            Text.literal("长期记忆管理").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 30;

        // 记忆数量
        TextWidget countWidget = new TextWidget(
            centerX - 100, y, 200, 15,
            Text.literal("共 " + memoryList.size() + " 条记忆"),
            this.textRenderer
        );
        this.addDrawableChild(countWidget);
        y += 25;

        // 记忆列表
        int listWidth = 300;
        int entryHeight = 30;
        int listX = centerX - listWidth / 2;

        for (int i = 0; i < VISIBLE_ENTRIES; i++) {
            int index = scrollOffset + i;
            if (index >= memoryList.size()) break;

            MemoryManager.LongTermMemoryEntry entry = memoryList.get(index);
            int entryY = y + i * entryHeight;

            // 记忆内容（截断显示）
            String displayText = entry.content;
            if (displayText.length() > 40) {
                displayText = displayText.substring(0, 37) + "...";
            }

            TextWidget contentWidget = new TextWidget(
                listX + 5, entryY + 5, listWidth - 110, 20,
                Text.literal("[" + entry.category + "] " + displayText),
                this.textRenderer
            );
            this.addDrawableChild(contentWidget);

            // 编辑按钮
            int buttonY = entryY + 5;
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("编辑"),
                button -> editMemory(entry.id)
            ).dimensions(listX + listWidth - 100, buttonY, 45, 18).build());

            // 删除按钮
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("删除").formatted(Formatting.RED),
                button -> deleteMemory(entry.id)
            ).dimensions(listX + listWidth - 50, buttonY, 45, 18).build());
        }

        y += VISIBLE_ENTRIES * entryHeight + 10;

        // 滚动按钮
        if (memoryList.size() > VISIBLE_ENTRIES) {
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("↑ 上翻"),
                button -> scrollUp()
            ).dimensions(centerX - 110, y, 80, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("下翻 ↓"),
                button -> scrollDown()
            ).dimensions(centerX + 30, y, 80, 20).build());

            y += 30;
        }

        // 新增记忆按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("+ 新增记忆"),
            button -> addNewMemory()
        ).dimensions(centerX - 60, y, 120, 20).build());
    }

    private void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
            this.clearAndInit();
        }
    }

    private void scrollDown() {
        if (scrollOffset + VISIBLE_ENTRIES < memoryList.size()) {
            scrollOffset++;
            this.clearAndInit();
        }
    }

    private void addNewMemory() {
        // 打开新增记忆对话框
        memoryManager.addLongTermMemory("custom", "新记忆内容", "玩家手动添加");
        memoryList = memoryManager.getAllLongTermMemory();
        this.clearAndInit();
    }

    private void editMemory(String id) {
        // 打开编辑对话框
    }

    private void deleteMemory(String id) {
        memoryManager.deleteLongTermMemory(id);
        memoryList = memoryManager.getAllLongTermMemory();
        this.clearAndInit();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
