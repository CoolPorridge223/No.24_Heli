/** 文件：上下机舱门的上下文输入；端侧：CLIENT；C2S：no_24_heli:cabin_door。 */
package org.DJB.no_24_heli.client.input;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.RopeSeatEntity;
import org.DJB.no_24_heli.network.CabinDoorPayload;
import org.lwjgl.glfw.GLFW;

/**
 * 职责：舱内或看向近处侧门时把可重绑 F 转为舱门意图；离舱后继续由开伞处理。
 * 引用 net.minecraft.client.option.KeyBinding 注册原版控制菜单，ClientPlayNetworking 只发送目标 ID。
 * 生命周期：客户端初始化注册，键盘/鼠标回调按下边沿调用；不修改门的权威状态。
 * 排查：共享 F 冲突查 HeliKeyBindingMixin；目标为空查侧门距离及准星遮挡。
 */
@Environment(EnvType.CLIENT)
public final class CabinDoorClient {
    /** 门与伞允许共享 F，但不占用原版交换副手的全局键映射。 */
    private static final KeyBinding DOOR = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.no_24_heli.cabin_door", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F, "category.no_24_heli"));

    /** 静态输入工具，无世界状态。 */
    private CabinDoorClient() {}

    /** 类初始化完成按键注册，net.minecraft.client.option.KeyBinding 不在服务端加载。 */
    public static void initialize() {}

    /** 提示读取实际绑定，键盘和鼠标重绑均自动更新。 */
    public static Text keyName() { return DOOR.getBoundKeyLocalizedText(); }

    /** 优先本机，再取准星命中的侧门；绳顶仅在原有允许登机范围内返回所属飞机。 */
    public static HelicopterEntity target() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.currentScreen != null) return null;
        if (client.player.getVehicle() instanceof HelicopterEntity heli) return heli;
        if (client.player.getVehicle() instanceof RopeSeatEntity rope && rope.canBoardHelicopter(client.player))
            return rope.helicopter();
        return client.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof HelicopterEntity heli
                && heli.cabin.canReach(client.player) ? heli : null;
    }

    /** 绳顶沿原右前副驾入口开门，其余由本侧位置决定。 */
    public static int side(HelicopterEntity heli) {
        var player = MinecraftClient.getInstance().player;
        return player.getVehicle() instanceof RopeSeatEntity ? 1 : heli.cabin.accessSide(player);
    }

    /** net.minecraft.client.Keyboard#onKey 只消费作用域内实际绑定，释放事件放回原版。 */
    public static boolean handleKey(int key, int scan, int action) {
        return DOOR.matchesKey(key, scan) && handle(action);
    }

    /** net.minecraft.client.Mouse#onMouseButton 支持重绑鼠标，采用相同按下边沿。 */
    public static boolean handleMouse(int button, int action) {
        return DOOR.matchesMouse(button) && handle(action);
    }

    /** 不在 GUI 中发包，不从客户端指定门侧；服务端再次推导并校验。 */
    private static boolean handle(int action) {
        HelicopterEntity heli = target();
        if (heli == null) return false;
        if (action == GLFW.GLFW_PRESS) ClientPlayNetworking.send(new CabinDoorPayload(heli.getId()));
        return action != GLFW.GLFW_RELEASE;
    }
}
