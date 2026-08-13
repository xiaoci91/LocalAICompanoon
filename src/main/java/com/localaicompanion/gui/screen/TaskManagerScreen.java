package com.localaicompanion.gui.screen;

import com.localaicompanion.task.Task;
import com.localaicompanion.task.TaskScheduler;
import com.localaicompanion.task.state.TaskState;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * 任务管理页面
 *
 * 功能：
 * - 查看当前任务队列
 * - 单独取消任务
 * - 一键清空全部任务
 */
public class TaskManagerScreen extends Screen {
    private final Screen parent;
    private final TaskScheduler taskScheduler;

    private List<Task> queuedTasks;
    private Task currentTask;

    public TaskManagerScreen(Screen parent, TaskScheduler taskScheduler) {
        super(Text.literal("任务管理"));
        this.parent = parent;
        this.taskScheduler = taskScheduler;
        this.queuedTasks = taskScheduler.getQueuedTasks();
        this.currentTask = taskScheduler.getCurrentTask();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 20;
        int listWidth = 300;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 50, y, 100, 20,
            Text.literal("任务管理").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 30;

        // 当前任务
        TextWidget currentLabel = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("【当前任务】").formatted(Formatting.BOLD, Formatting.GREEN),
            this.textRenderer
        );
        this.addDrawableChild(currentLabel);
        y += 20;

        if (currentTask != null && currentTask.getState().isActive()) {
            String taskText = String.format("%s - %s (%.0f%%)",
                currentTask.getStandardTask().getIntentType().getDisplayName(),
                currentTask.getState().getDisplayName(),
                currentTask.getProgress() * 100);

            TextWidget currentTaskWidget = new TextWidget(
                centerX - listWidth / 2 + 5, y, listWidth - 70, 20,
                Text.literal(taskText),
                this.textRenderer
            );
            this.addDrawableChild(currentTaskWidget);

            // 取消按钮
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("取消").formatted(Formatting.RED),
                button -> cancelTask(currentTask.getInstanceId())
            ).dimensions(centerX + listWidth / 2 - 60, y, 55, 20).build());
        } else {
            TextWidget noTask = new TextWidget(
                centerX - listWidth / 2, y, listWidth, 15,
                Text.literal("暂无正在执行的任务").formatted(Formatting.GRAY),
                this.textRenderer
            );
            this.addDrawableChild(noTask);
        }
        y += 30;

        // 等待队列
        TextWidget queueLabel = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("【等待队列】(" + queuedTasks.size() + "个)").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(queueLabel);
        y += 20;

        // 任务列表
        int maxDisplay = 6;
        for (int i = 0; i < Math.min(queuedTasks.size(), maxDisplay); i++) {
            Task task = queuedTasks.get(i);
            String taskText = String.format("%d. %s (优先级:%d)",
                i + 1,
                task.getStandardTask().getIntentType().getDisplayName(),
                task.getStandardTask().getPriority());

            TextWidget taskWidget = new TextWidget(
                centerX - listWidth / 2 + 5, y + i * 22, listWidth - 70, 18,
                Text.literal(taskText),
                this.textRenderer
            );
            this.addDrawableChild(taskWidget);

            // 取消按钮
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal("取消").formatted(Formatting.RED),
                button -> cancelTask(task.getInstanceId())
            ).dimensions(centerX + listWidth / 2 - 60, y + i * 22, 55, 18).build());
        }

        if (queuedTasks.size() > maxDisplay) {
            y += maxDisplay * 22;
            TextWidget more = new TextWidget(
                centerX - listWidth / 2, y, listWidth, 15,
                Text.literal("...还有 " + (queuedTasks.size() - maxDisplay) + " 个任务"),
                this.textRenderer
            );
            this.addDrawableChild(more);
            y += 20;
        } else {
            y += Math.max(queuedTasks.size(), 1) * 22;
        }

        y += 10;

        // 一键清空
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("清空全部任务").formatted(Formatting.RED),
            button -> clearAllTasks()
        ).dimensions(centerX - 75, y, 150, 20).build());
    }

    private void cancelTask(String taskId) {
        taskScheduler.cancelTask(taskId);
        queuedTasks = taskScheduler.getQueuedTasks();
        currentTask = taskScheduler.getCurrentTask();
        this.clearAndInit();
    }

    private void clearAllTasks() {
        taskScheduler.clearAllTasks();
        queuedTasks = taskScheduler.getQueuedTasks();
        currentTask = taskScheduler.getCurrentTask();
        this.clearAndInit();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
