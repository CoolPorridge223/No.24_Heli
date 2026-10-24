/** 文件：客户端下机按键拦截；端侧：CLIENT；通道：no_24_heli:controls（DISMOUNT 位）。 */
package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.RopeSeatEntity;
import org.DJB.no_24_heli.network.ControlPayload;


import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.input.Input;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：在原版玩家消费输入前截住蹲下/跳跃，只把 Shift 上升沿转换为下机请求。
 * 引用 MC API：net.minecraft.client.input.KeyboardInput#tick 生成原版输入；
 *      net.minecraft.client.MinecraftClient#currentScreen 防止 GUI 中误触。
 * 生命周期：客户端每 tick 调用；只发 C2S 意图，不修改门、座位或载具状态。
 * 端侧/线程：CLIENT 渲染/客户端主线程；公共端不能加载本类。
 * 排查：Shift 无响应查按键冲突、GUI 焦点和 C2S 注册；连续触发送机查上升沿状态是否被重置。
 */
@Mixin(KeyboardInput.class)
public class HeliDismountMixin extends Input {
    /** 上一 tick 的 Shift 状态，用于只发送按下边沿；服务端自行维护二次确认窗口。 */
    @Unique private boolean no24$shiftHeld;
    /**
     * 功能：拦截蹲下/跳跃输入并发送下机意图；伞上蹲下只用于收伞，不当作未经确认的切伞。
     * 注入点：KeyboardInput#tick 尾部，此时原版已生成 sneaking/jumping，但尚未被玩家移动消费。
     * 副作用：清空直升机/绳索乘员的蹲下和跳跃，禁用创造飞行；不修改服务端状态。
     */
    @Inject(method="tick",at=@At("TAIL"))
    private void confirmExit(boolean slowDown,float factor,CallbackInfo ci){
        var client=MinecraftClient.getInstance();boolean pressed=sneaking;
        // 滑翔伞自动着陆收起；空中蹲下不作为未经确认的切伞，避免误触直接坠落。
        if(client.player!=null&&client.player.getVehicle() instanceof org.DJB.no_24_heli.entity.ParachuteEntity){
            sneaking=false;jumping=false;client.player.getAbilities().flying=false;
        }
        if(client.player!=null&&(client.player.getVehicle() instanceof HelicopterEntity||client.player.getVehicle() instanceof RopeSeatEntity)){
            if(pressed&&!no24$shiftHeld&&client.currentScreen==null)
                ClientPlayNetworking.send(new ControlPayload(0,0,0,0,ControlPayload.DISMOUNT));
            sneaking=false;
            // 原版双击跳跃在消费 Input 前被阻止；直升机总距直接读取 jumpKey，不受影响。
            jumping=false;
            client.player.getAbilities().flying=false;
        }
        no24$shiftHeld=pressed;
    }
}
