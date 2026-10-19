/** 文件：跳伞客户端输入及渲染注册；端侧：CLIENT；C2S：parachute_control。 */
package org.DJB.no_24_heli.client.input;

import org.DJB.no_24_heli.client.hud.ParachuteFoldingHud;
import org.DJB.no_24_heli.client.render.ParachutePackRenderer;
import org.DJB.no_24_heli.client.render.ParachuteRenderer;
import org.DJB.no_24_heli.entity.ParachuteEntity;
import org.DJB.no_24_heli.network.ParachutePayload;
import org.DJB.no_24_heli.registry.ModParachutes;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.item.ParachuteItem;
import net.minecraft.client.item.ModelPredicateProviderRegistry;


import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 职责：仅提交开伞边沿与 WASD 意图，不修改玩家权威坐标或速度。
 * 引用：Fabric ClientTickEvents 采集输入，EntityRendererRegistry 和特征回调注册外观。
 * 生命周期：客户端初始化一次；键盘 Mixin 仅在自由落体有伞包时拦截开伞键。
 * 排查：F 失效查焦点/资格/绑定；交换副手被占查共享键恢复与 Keyboard 回调作用域。
 */
@Environment(EnvType.CLIENT)
public final class ParachuteClient {
    /** 默认 F，可在原版控制中重绑；共享键由 HeliKeyBindingMixin 还原原版映射。 */
    private static final KeyBinding OPEN = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.no_24_heli.parachute", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F, "category.no_24_heli"));
    /** 只缓存一次按下边沿，GUI 打开或离开世界时丢弃。 */
    private static boolean deployRequested;
    /** 当前伞实体 ID：仅初次乘骑时清除原版 Shift 脱离提示，避免每 tick 覆盖其他消息。 */
    private static int mountedCanopy = -1;

    /** 无实例，输入状态仅属于当前客户端。 */
    private ParachuteClient() {}

    /** 原版 net.minecraft.client.option.KeyBinding 提供当前实际绑定名称，提示随重绑更新。 */
    public static net.minecraft.text.Text keyName() { return OPEN.getBoundKeyLocalizedText(); }

    /**
     * net.minecraft.client.Keyboard#onKey 的限定作用域入口；按住不重复开伞。
     * 只拦截有效开伞或已开伞的同键动作，释放事件放行，离开该状态恢复原版交换副手。
     */
    public static boolean handleKey(int key, int scan, int action) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.currentScreen != null || !OPEN.matchesKey(key, scan)
                || !(ParachuteEntity.canDeploy(client.player)
                || client.player.getVehicle() instanceof ParachuteEntity)) return false;
        if (action == GLFW.GLFW_PRESS && canPull(client)) deployRequested = true;
        return action != GLFW.GLFW_RELEASE;
    }

    /** 原版允许把开伞键改为鼠标键；只在同样的自由落体/乘伞作用域消费。 */
    public static boolean handleMouse(int button, int action) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.currentScreen != null || !OPEN.matchesMouse(button)
                || !(ParachuteEntity.canDeploy(client.player)
                || client.player.getVehicle() instanceof ParachuteEntity)) return false;
        if (action == GLFW.GLFW_PRESS && canPull(client)) deployRequested = true;
        return action != GLFW.GLFW_RELEASE;
    }

    /** 开伞与卡伞拉绳共用 F 边沿，其他已展开状态仍按原作用域消费共享键。 */
    private static boolean canPull(MinecraftClient client) {
        return ParachuteEntity.canDeploy(client.player)
                || client.player.getVehicle() instanceof ParachuteEntity canopy && canopy.needsPull();
    }

    /**
     * 注册 net.fabricmc.fabric.api.client.rendering.v1 的实体和玩家背包特征，tick 中发送意图。
     * GUI 打开时输入回中；断线清除待发请求；服务端仍重新验证资格与所属伞。
     */
    public static void initialize() {
        // 原版谓词限制在 0～1：完整、未折叠、损坏分别为 0、0.5、1，不替换物品或组件。
        ModelPredicateProviderRegistry.register(ModParachutes.PARACHUTE,No_24_heli.id("pack_state"),
                (stack,world,entity,seed)->ParachuteItem.isLost(stack)?1:ParachuteItem.isPacked(stack)?0:.5f);
        EntityRendererRegistry.register(ModParachutes.CANOPY, ParachuteRenderer::new);
        HudRenderCallback.EVENT.register(ParachuteFoldingHud::draw);
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerEntityRenderer playerRenderer)
                helper.register(new ParachutePackRenderer(playerRenderer));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) {
                deployRequested = false; mountedCanopy = -1; return;
            }
            boolean active = client.player.getVehicle() instanceof ParachuteEntity;
            int currentCanopy = active ? client.player.getVehicle().getId() : -1;
            // net.minecraft.client.gui.hud.InGameHud#setOverlayMessage 清除原版骑乘提示，不替换成操作说明。
            if (currentCanopy != mountedCanopy && active)
                client.inGameHud.setOverlayMessage(net.minecraft.text.Text.empty(), false);
            mountedCanopy = currentCanopy;
            boolean deploy = deployRequested && client.currentScreen == null && canPull(client);
            deployRequested = false;
            if (!active && !deploy) return;
            boolean input = client.currentScreen == null;
            float forward = input ? (client.options.forwardKey.isPressed() ? 1 : 0)
                    - (client.options.backKey.isPressed() ? 1 : 0) : 0;
            float turn = input ? (client.options.rightKey.isPressed() ? 1 : 0)
                    - (client.options.leftKey.isPressed() ? 1 : 0) : 0;
            ClientPlayNetworking.send(new ParachutePayload(forward, turn, deploy,
                    net.minecraft.util.math.MathHelper.wrapDegrees(client.player.getYaw()), client.player.getPitch()));
        });
    }
}
