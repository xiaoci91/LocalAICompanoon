package com.localaicompanion.gui.screen;

import com.localaicompanion.memory.MemoryManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 记忆编辑界面
 */
public class MemoryEditScreen extends Screen {
    private final Screen parent;
    private final MemoryManager memoryManager;
    private final String memoryId;
    private final boolean isNew;

    private TextFieldWidget categoryField;
    private TextFieldWidget contentField;

    public MemoryEditScreen(Screen parent, MemoryManager memoryManager, String memoryId) {
        super(Text.literal(memoryId == null ? "新增记忆" : "编辑记忆"));
        this.parent = parent;
        this.memoryManager = memoryManager;
        this.memoryId = memoryId;
        this.isNew = (memoryId == null);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 30;
        int fieldWidth = 300;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 50, y, 100, 20,
            Text.literal(isNew ? "新增记忆" : "编辑记忆").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 40;

        // 分类
        TextWidget categoryLabel = new TextWidget(
            centerX - fieldWidth / 2, y, 60, 20,
            Text.literal("分类:"),
            this.textRenderer
        );
        this.addDrawableChild(categoryLabel);

        categoryField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + 65, y,
            fieldWidth - 65, 20,
            Text.literal("分类")
        );
        categoryField.setMaxLength(50);

        // 获取当前值
        if (!isNew) {
            MemoryManager.LongTermMemoryEntry entry = memoryManager.getLongTermMemory(memoryId);
            if (entry != null) {
                categoryField.setText(entry.category);
            }
        } else {
            categoryField.setText("custom");
        }
        this.addDrawableChild(categoryField);
        y += 30;

        // 内容
        TextWidget contentLabel = new TextWidget(
            centerX - fieldWidth / 2, y, 60, 20,
            Text.literal("内容:"),
            this.textRenderer
        );
        this.addDrawableChild(contentLabel);
        y += 20;

        contentField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2, y,
            fieldWidth, 20,
            Text.literal("内容")
        );
        contentField.setMaxLength(500);

        if (!isNew) {
            MemoryManager.LongTermMemoryEntry entry = memoryManager.getLongTermMemory(memoryId);
            if (entry != null) {
                contentField.setText(entry.content);
            }
        }
        this.addDrawableChild(contentField);
        y += 35;

        // 提示
        TextWidget tip = new TextWidget(
            centerX - fieldWidth / 2, y, fieldWidth, 15,
            Text.literal("输入记忆内容，AI会记住这些信息").formatted(Formatting.GRAY),
            this.textRenderer
        );
        this.addDrawableChild(tip);
        y += 30;

        // 保存按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("保存"),
            button -> saveAndClose()
        ).dimensions(centerX - 110, y, 100, 20).build());

        // 取消按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("取消"),
            button -> {
                if (this.client != null) {
                    this.client.setScreen(parent);
                }
            }
        ).dimensions(centerX + 10, y, 100, 20).build());
    }

    private void saveAndClose() {
        String category = categoryField.getText().trim();
        String content = contentField.getText().trim();

        if (content.isEmpty()) {
            return;
        }

        if (isNew) {
            memoryManager.addLongTermMemory(category, content, "玩家手动添加");
        } else {
            memoryManager.updateLongTermMemory(memoryId, category, content);
        }

        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
