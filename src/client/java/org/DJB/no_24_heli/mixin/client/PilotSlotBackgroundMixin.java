/** 文件：专用头槽的边框、空槽图形与提示；端侧：CLIENT。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.DJB.no_24_heli.screen.PilotEquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：为追加槽绘制原版风格凹框及耳麦/目镜轮廓，不覆盖其他容器背景。
 * 引用 net.minecraft.client.gui.screen.ingame.HandledScreen#drawSlot 在原版物品前绘制，无对应槽背景事件。
 * 生命周期：客户端 GUI 渲染；排查：只匹配专用槽，创造包装槽按页签及编号判别。
 */
@Environment(EnvType.CLIENT)
@Mixin(HandledScreen.class)
public abstract class PilotSlotBackgroundMixin {
    /** 原版当前悬停槽，用于空槽中文/英文提示。 */
    @Shadow protected Slot focusedSlot;

    /** 返回 0 耳麦、1 目镜、-1 非专用槽，创造常规列表中的相同编号不匹配。 */
    @Unique private int heli$kind(Slot slot) {
        if (slot == null) return -1;
        if (slot instanceof PilotEquipmentSlot) return slot.getIndex() - PilotEquipment.HEADSET;
        var client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CreativeInventoryScreen screen && screen.getScreenHandler().slots.size() == 49
                && slot.getIndex() >= 46 && slot.getIndex() <= 47) return slot.getIndex() - 46;
        return -1;
    }

    /** drawSlot 已平移到 GUI 原点；深浅边框不改变点击范围，图标仅空槽出现。 */
    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void slotFrame(DrawContext context, Slot slot, CallbackInfo ci) {
        int kind = heli$kind(slot);
        if (kind < 0) return;
        int x = slot.x, y = slot.y;
        context.fill(x - 1, y - 1, x + 17, y + 17, 0xff373737);
        context.fill(x, y, x + 17, y + 17, 0xffffffff);
        context.fill(x, y, x + 16, y + 16, 0xff8b8b8b);
        if (slot.hasStack()) return;
        int ink = 0xffb9b9b9;
        if (kind == 0) {
            context.fill(x + 4, y + 3, x + 12, y + 5, ink);
            context.fill(x + 3, y + 4, x + 5, y + 12, ink);
            context.fill(x + 11, y + 4, x + 13, y + 12, ink);
            context.fill(x + 11, y + 12, x + 15, y + 13, ink);
        } else {
            context.fill(x + 2, y + 6, x + 7, y + 10, ink);
            context.fill(x + 9, y + 6, x + 14, y + 10, ink);
            context.fill(x + 7, y + 6, x + 9, y + 8, ink);
        }
    }

    /** 空槽不会触发原版物品 tooltip，因此追加本地化槽位说明。 */
    @Inject(method = "render", at = @At("TAIL"))
    private void slotTooltip(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        int kind = heli$kind(focusedSlot);
        if (kind >= 0 && !focusedSlot.hasStack()) context.drawTooltip(MinecraftClient.getInstance().textRenderer,
                Text.translatable(kind == 0 ? "slot.no_24_heli.headset" : "slot.no_24_heli.goggles"), mouseX, mouseY);
    }
}
