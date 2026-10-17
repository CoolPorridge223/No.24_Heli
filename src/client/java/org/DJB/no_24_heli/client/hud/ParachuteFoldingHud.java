/** 文件：连续五秒叠伞进度；端侧：CLIENT。 */
package org.DJB.no_24_heli.client.hud;

import org.DJB.no_24_heli.item.ParachuteItem;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * 职责：屏幕中央圆圈和“正在叠伞”文字，使用原版连续使用计时，不累积取消后的进度。
 * 引用：net.minecraft.entity.LivingEntity#getItemUseTime 提供按住时长，HudRenderCallback 绘制。
 * 生命周期：客户端初始化注册；松手、切物品或打开界面即隐藏，只显示而不修改物品组件。
 * 排查：完成后无法穿戴查服务端 finishUsing；圆圈不归零查原版停止使用的状态。
 */
@Environment(EnvType.CLIENT)
public final class ParachuteFoldingHud {
    /** GUI 像素半径十八，环宽三，顶部起顺时针显示已完成比例。 */
    private static final int RADIUS = 18, WIDTH = 3;
    /** 无实例，由客户端 HUD 回调调用。 */
    private ParachuteFoldingHud() {}

    /** 使用原版 GUI fill 栅格化圆环，避免新增纹理或依赖渲染器全局状态。 */
    public static void draw(DrawContext context, RenderTickCounter counter) {
        var client = MinecraftClient.getInstance(); var player = client.player;
        if (player == null || client.options.hudHidden || client.currentScreen != null || !player.isUsingItem()
                || !ParachuteItem.canFold(player.getActiveItem())) return;
        double progress = Math.clamp((player.getItemUseTime() + counter.getTickDelta(false))
                / ParachuteItem.FOLD_TICKS, 0, 1);
        int cx = context.getScaledWindowWidth() / 2, cy = context.getScaledWindowHeight() / 2;
        for (int y = -RADIUS; y <= RADIUS; y++) for (int x = -RADIUS; x <= RADIUS; x++) {
            double radius = Math.hypot(x, y);
            if (radius < RADIUS - WIDTH || radius > RADIUS) continue;
            double angle = (Math.atan2(x, -y) + 2 * Math.PI) % (2 * Math.PI);
            context.fill(cx + x, cy + y, cx + x + 1, cy + y + 1,
                    angle <= progress * 2 * Math.PI ? 0xffffd477 : 0xb0404b59);
        }
        context.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("hud.no_24_heli.folding"),
                cx, cy + RADIUS + 8, 0xffffd477);
    }
}
