package org.DJB.no_24_heli.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.*;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.gui.screen.option.OptionsScreen;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.MathHelper;
import org.DJB.no_24_heli.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** 客户端仅采集输入并展示服务器状态；驾驶者身份由服务器再次验证。 */
public class No_24_heliClient implements ClientModInitializer {
    private static KeyBinding bind(String name,int key){return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.no_24_heli."+name,InputUtil.Type.KEYSYM,key,"category.no_24_heli"));}
    private static final KeyBinding START=bind("engine",GLFW.GLFW_KEY_R), ALT=bind("altitude",GLFW.GLFW_KEY_H), TRIM=bind("trim",GLFW.GLFW_KEY_J), CRUISE=bind("cruise",GLFW.GLFW_KEY_I), GEAR=bind("gear",GLFW.GLFW_KEY_G), ROPE=bind("rope",GLFW.GLFW_KEY_B), EXIT=bind("rappel",GLFW.GLFW_KEY_Y), MENU=bind("service",GLFW.GLFW_KEY_O), MOUSE=bind("mouse",GLFW.GLFW_KEY_N), MAP=bind("map",GLFW.GLFW_KEY_M), AUTO=bind("autopilot",GLFW.GLFW_KEY_U), AUTO_FLIGHT=bind("auto_flight",GLFW.GLFW_KEY_K), LEFT=bind("pedal_left",GLFW.GLFW_KEY_Z), RIGHT=bind("pedal_right",GLFW.GLFW_KEY_V);
    /** 原版控制菜单支持把视角锁定绑定到任意键盘键或鼠标键。 */
    private static final KeyBinding FOLLOW=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.no_24_heli.follow",InputUtil.Type.MOUSE,GLFW.GLFW_MOUSE_BUTTON_MIDDLE,"category.no_24_heli"));
    public static boolean followMouseMatches(int button){return FOLLOW.matchesMouse(button);}
    public static boolean followKeyMatches(int key,int scan){return FOLLOW.matchesKey(key,scan);}
    public static boolean mapKeyMatches(int key,int scan){return MAP.matchesKey(key,scan);}
    private boolean tabHeld;
    /** 绳上 E 只发送按下边沿，长按不能在到顶时自动入舱。 */
    private boolean ropeBoardHeld;
    private boolean mouseControl=true;
    private boolean migrated;
    private boolean migratedMap;
    /** 1.2 的 M 让给地图；仅迁移仍为旧默认值的鼠标切换键。 */
    private void migrateMapKey(net.minecraft.client.MinecraftClient client){
        if(migratedMap)return;migratedMap=true;
        var marker=FabricLoader.getInstance().getConfigDir().resolve("no_24_heli_keys_v3.marker");
        if(Files.exists(marker))return;
        try{
            if(MOUSE.matchesKey(GLFW.GLFW_KEY_M,0)){MOUSE.setBoundKey(InputUtil.fromKeyCode(GLFW.GLFW_KEY_N,0));KeyBinding.updateKeysByCode();client.options.write();}
            Files.writeString(marker,"mouse-default=N;map-default=M");
        }catch(java.io.IOException e){No_24_heli.LOGGER.warn("无法保存地图按键迁移标记",e);}
    }
    /** 旧版默认 K 仅迁移一次；其他自定义按键保持原值。 */
    private void migrateTrimKey(net.minecraft.client.MinecraftClient client) {
        if (migrated) return;
        migrated = true;
        var marker = FabricLoader.getInstance().getConfigDir().resolve("no_24_heli_keys_v2.marker");
        if (Files.exists(marker)) return;
        try {
            if (TRIM.matchesKey(GLFW.GLFW_KEY_K, 0)) {
                TRIM.setBoundKey(InputUtil.fromKeyCode(GLFW.GLFW_KEY_J, 0));
                KeyBinding.updateKeysByCode(); client.options.write();
            }
            Files.writeString(marker, "trim-default=J");
        } catch (java.io.IOException e) { No_24_heli.LOGGER.warn("无法保存按键迁移标记", e); }
    }
    private float oldYaw,oldPitch;
    private int vehicleId=-1;
    private final Map<Integer,RotorSound> sounds=new HashMap<>();
    private static int pressed(KeyBinding key,int bit){int result=0;while(key.wasPressed())result=bit;return result;}
    /** 将入口置于水平中央的空行，避开原版标题和已有按钮。 */
    private static int settingsButtonY(net.minecraft.client.gui.screen.Screen screen,int width,int height){
        var buttons=Screens.getButtons(screen);
        for(int y=height-52;y>=34;y--){
            final int row=y;

            if(buttons.stream().noneMatch(b->b.visible&&b.getX()<width/2+102&&b.getX()+b.getWidth()>width/2-102
                    &&b.getY()<row+22&&b.getY()+b.getHeight()>row-2))return row;
        }
        return 32;
    }
    @Override public void onInitializeClient(){
        ExploredMap.register();RadioLibrary.initialize();ServerRadioCache.register();
        net.minecraft.client.render.block.entity.BlockEntityRendererFactories.register(No_24_heli.HELIPAD_ENTITY,HelipadRenderer::new);
        EntityRendererRegistry.register(No_24_heli.HELI,HelicopterRenderer::new);
        EntityRendererRegistry.register(No_24_heli.ROPE,RopeRenderer::new);
        HandledScreens.register(No_24_heli.SCREEN,HeliScreen::new);
        HudRenderCallback.EVENT.register(HeliHud::draw);
        ClientPlayNetworking.registerGlobalReceiver(RadarContactsPayload.ID,(packet,context)->context.client().execute(()->{
            HeliRadar.targets();HeliRadar.receive(packet);
        }));
        ClientPlayNetworking.registerGlobalReceiver(HelipadMapPayload.ID,(packet,context)->context.client().execute(()->{
            HeliRadar.targets();HeliRadar.receive(packet);
        }));
        ClientPlayNetworking.registerGlobalReceiver(AirspacePayload.ID,(packet,context)->context.client().execute(()->{HeliRadar.targets();HeliRadar.zones=packet.zones();}));
        ClientPlayNetworking.registerGlobalReceiver(RadarPayload.ID,(packet,context)->context.client().execute(()->{
            if(context.client().currentScreen instanceof RadarScreen screen)screen.receive(packet);
            else if(!packet.update())context.client().setScreen(new RadarScreen(packet));
        }));
        ClientPlayNetworking.registerGlobalReceiver(DifficultyPayload.ID, (packet, context) -> context.client().execute(() -> {
            if (context.client().currentScreen instanceof HeliSettingsScreen screen) screen.receiveDifficulty(packet);
        }));
        ClientPlayNetworking.registerGlobalReceiver(SettingsPayload.ID, (packet, context) -> context.client().execute(() -> {
            if (context.client().currentScreen instanceof HeliSettingsScreen screen) screen.receive(packet);
        }));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // 仅原版“选项”提供一个入口；重复初始化先移除旧按钮，不注入暂停或模组子页面。
            if(screen.getClass()==OptionsScreen.class){
                Screens.getButtons(screen).removeIf(b->b.getMessage().equals(Text.translatable("screen.no_24_heli.settings")));
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("screen.no_24_heli.settings"),
                        button -> client.setScreen(new HeliSettingsScreen(screen))).dimensions(width/2-100, settingsButtonY(screen,width,height), 200, 20).build());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            migrateTrimKey(client);migrateMapKey(client); HeliView.tick(client);ExploredMap.tick();ServerRadioCache.tick();RadioManager.tick(client);GroundProximityWarning.tick(client);
            if(client.world==null||client.player==null){sounds.clear();vehicleId=-1;HeliRadar.reset();return;}
            // 所有附近直升机都播放连续音源，转速决定音高和音量。
            sounds.entrySet().removeIf(e->e.getValue().isDone());
            for(var entity:client.world.getEntities())if(entity instanceof HelicopterEntity h&&h.flight.rpm>.015&&!sounds.containsKey(h.getId())){
                var sound=new RotorSound(h);sounds.put(h.getId(),sound);client.getSoundManager().play(sound);
            }
            int action=pressed(START,1)|pressed(ALT,2)|pressed(TRIM,4)|pressed(CRUISE,8)|pressed(GEAR,16)|pressed(ROPE,32)|pressed(EXIT,64)|pressed(MENU,128)|pressed(AUTO,ControlPayload.AUTOPILOT)|pressed(AUTO_FLIGHT,ControlPayload.AUTO_FLIGHT);
            if(MOUSE.wasPressed()&&client.currentScreen==null)mouseControl=!mouseControl;
            if(MAP.wasPressed()){
                if(client.currentScreen instanceof HeliMapScreen)client.setScreen(null);
                else if(client.currentScreen==null&&client.player.getVehicle() instanceof HelicopterEntity)client.setScreen(new HeliMapScreen());
            }
            // Tab使用按下边沿打开圆盘；松手由Screen确认，不能让同一次长按反复重开。
            boolean tab=InputUtil.isKeyPressed(client.getWindow().getHandle(),GLFW.GLFW_KEY_TAB);
            if(tab&&!tabHeld&&client.currentScreen==null&&client.player.getVehicle() instanceof HelicopterEntity)client.setScreen(new RadioWheelScreen());
            tabHeld=tab;
            boolean ropeBoard=InputUtil.isKeyPressed(client.getWindow().getHandle(),GLFW.GLFW_KEY_E);
            if(client.player.getVehicle() instanceof RopeSeatEntity){
                float climb=client.currentScreen==null?(client.options.forwardKey.isPressed()?1:0)-(client.options.backKey.isPressed()?1:0):0;
                int ropeAction=client.currentScreen==null&&ropeBoard&&!ropeBoardHeld?ControlPayload.ROPE_BOARD:0;
                ClientPlayNetworking.send(new ControlPayload(0,0,0,climb,ropeAction));
            }else if(client.currentScreen==null&&!(client.player.getVehicle() instanceof HelicopterEntity)
                    &&(action&ControlPayload.EXIT_ROPE)!=0)
                ClientPlayNetworking.send(new ControlPayload(0,0,0,0,ControlPayload.EXIT_ROPE));
            ropeBoardHeld=ropeBoard;
            if(!(client.player.getVehicle() instanceof HelicopterEntity heli)){vehicleId=-1;return;}

            if(heli.age%40==0&&(heli.flight.restricted||((heli.flight.engine<20||heli.flight.fuel<40)&&heli.flight.running)))
                client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(No_24_heli.ALARM,1,.6f));

            if(heli.age%25==0&&heli.flight.rotor<50&&heli.flight.rpm>.3)client.player.playSound(No_24_heli.DAMAGE_SOUND,.3f,1);
            float yaw=client.player.getYaw(),pitch=client.player.getPitch();
            if(vehicleId!=heli.getId()){oldYaw=yaw;oldPitch=pitch;vehicleId=heli.getId();}
            boolean airborneMouse=mouseControl&&!heli.isOnGround()&&heli.flight.armed;
            float mouseRoll=airborneMouse?MathHelper.clamp(MathHelper.wrapDegrees(yaw-oldYaw)*.13f,-1,1):0;
            float mousePitch=airborneMouse?MathHelper.clamp((pitch-oldPitch)*-.13f,-1,1):0;
            oldYaw=yaw;oldPitch=pitch;
            float cyclicPitch=0,cyclicRoll=0,pedal=0,collective=0;
            if(client.currentScreen==null){
                cyclicPitch=(client.options.backKey.isPressed()?1:0)-(client.options.forwardKey.isPressed()?1:0)+mousePitch;
                cyclicRoll=(client.options.rightKey.isPressed()?1:0)-(client.options.leftKey.isPressed()?1:0)+mouseRoll;
                pedal=(RIGHT.isPressed()||InputUtil.isKeyPressed(client.getWindow().getHandle(),GLFW.GLFW_KEY_E)?1:0)-(LEFT.isPressed()||InputUtil.isKeyPressed(client.getWindow().getHandle(),GLFW.GLFW_KEY_Q)?1:0);
                collective=(client.options.jumpKey.isPressed()?1:0)-(InputUtil.isKeyPressed(client.getWindow().getHandle(),GLFW.GLFW_KEY_LEFT_CONTROL)?1:0);
            }else action=0;
            ClientPlayNetworking.send(new ControlPayload(cyclicPitch,cyclicRoll,pedal,collective,action));
        });
    }
}
