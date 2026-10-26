/** 文件：Ctrl 加座位键的机内换座输入；端侧：CLIENT；通道：no_24_heli:controls（C2S）。 */
package org.DJB.no_24_heli.client.input;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.network.ControlPayload;
import org.lwjgl.glfw.GLFW;

/**
 * 职责：只在机内接管 Ctrl+1..4 的按下边沿，交给现有每 tick 控制包，不写座位状态。
 * 引用：net.minecraft.client.Keyboard 事件保留组合键修饰信息，KeyBinding 提供控制菜单及重绑匹配。
 * 生命周期：客户端初始化注册四键，HeliKeyboardMixin 分派事件，END_CLIENT_TICK 消费一次意图。
 * 端侧/线程：仅客户端主线程；占座与驾驶权由服务端复核。
 * 排查：数字快捷栏失效查 HeliKeyBindingMixin 的共享键恢复；重复换座查 GLFW_PRESS；误下降查 heldChords。
 */
@Environment(EnvType.CLIENT)
public final class CabinSeatClient {
    /** 顺序为主驾、副驾、左后、右后；重绑只改变数字键，仍要求按住任一 Ctrl。 */
    private static final KeyBinding[] KEYS = {
            bind("pilot", GLFW.GLFW_KEY_1), bind("copilot", GLFW.GLFW_KEY_2),
            bind("rear_left", GLFW.GLFW_KEY_3), bind("rear_right", GLFW.GLFW_KEY_4)};
    /** 最后按下的目标与仍按住的组合键掩码；长按不重复，离机或打开界面后清除。 */
    private static int pendingAction, heldChords;

    /** 无实例；输入状态只属于本地客户端。 */
    private CabinSeatClient() {}

    /** net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper 将座位键加入原版控制菜单。 */
    private static KeyBinding bind(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.no_24_heli.seat_" + name,
                InputUtil.Type.KEYSYM, key, "category.no_24_heli"));
    }

    /** 客户端初始化时确保四键完成注册；不在服务端加载 net.minecraft.client.option.KeyBinding。 */
    public static void init() {}

    /**
     * 功能：机内消费 Ctrl 加座位键的按下/重复事件，释放继续交给原版以清理旧按住状态。
     * 引用 MC API：net.minecraft.client.option.KeyBinding#matchesKey 支持重绑；MinecraftClient 读取乘骑和 GUI。
     * 端侧：客户端主线程；参数：key/scan/action/modifiers 为 GLFW 事件；返回：是否取消原版处理。
     * 副作用：缓存一个目标动作；排查：GUI、机外及未按 Ctrl 时保持原版数字键行为。
     */
    public static boolean handleKey(int key, int scan, int action, int modifiers) {
        for (int seat = 0; seat < KEYS.length; seat++) {
            if (!KEYS[seat].matchesKey(key, scan)) continue;
            if (action == GLFW.GLFW_RELEASE) { heldChords &= ~(1 << seat); continue; }
            var client = MinecraftClient.getInstance();
            if ((modifiers & GLFW.GLFW_MOD_CONTROL) == 0 || client.currentScreen != null || client.player == null
                    || !(client.player.getVehicle() instanceof HelicopterEntity)) return false;
            heldChords |= 1 << seat;
            if (action == GLFW.GLFW_PRESS) pendingAction = ControlPayload.SEAT_PILOT << seat;
            return true;
        }
        return false;
    }

    /**
     * 功能：每 tick 取走一次意图，界面打开/离机后丢弃排队请求。
     * 引用 MC API：net.minecraft.client.util.InputUtil#isKeyPressed 检查左右 Ctrl 的真实状态。
     * 端侧：客户端主线程；参数：client 为本地客户端；返回：零或单个座位动作位。
     * 副作用：清除队列和过期组合键；排查：同 tick 多次按键以最后目标为准，不组合多个目标位。
     */
    public static int pollAction(MinecraftClient client) {
        int action = pendingAction; pendingAction = 0;
        if (client.currentScreen != null || client.player == null || !(client.player.getVehicle() instanceof HelicopterEntity)) {
            heldChords = 0; return 0;
        }
        long window = client.getWindow().getHandle();
        if (!InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                && !InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL)) heldChords = 0;
        return action;
    }

    /** Ctrl 用作换座组合键期间不同时降低总距；net.minecraft.client.Keyboard 的释放事件解除屏蔽。 */
    public static boolean suppressCollective() { return heldChords != 0; }
}
