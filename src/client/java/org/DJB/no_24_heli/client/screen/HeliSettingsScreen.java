package org.DJB.no_24_heli.client.screen;

import org.DJB.no_24_heli.data.HeliConfig;
import org.DJB.no_24_heli.network.DifficultyPayload;
import org.DJB.no_24_heli.network.SettingsPayload;



import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** 飞行模式为个人配置；爆炸破坏与着火由服务器确认管理员权限，主菜单编辑本机默认值。 */
public class HeliSettingsScreen extends Screen {
    private final Screen parent;
    private ButtonWidget destructionButton,difficultyButton;
    private int mode;
    private boolean difficultyEditable,difficultyPending;
    private boolean destruction, editable, pending;
    private Text status = Text.empty();
    public HeliSettingsScreen(Screen parent) { super(Text.translatable("screen.no_24_heli.settings")); this.parent = parent; }
    @Override protected void init() {
        destructionButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> change())
                .dimensions(width / 2 - 120, height / 2 - 38, 240, 20).build());
        difficultyButton=addDrawableChild(ButtonWidget.builder(Text.empty(),button->changeDifficulty())
                .dimensions(width/2-120,height/2-12,240,20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(width / 2 - 100, height / 2 + 82, 200, 20).build());
        if (client.world != null) {
            editable = false; pending = true; status = Text.translatable("screen.no_24_heli.loading");
            ClientPlayNetworking.send(new SettingsPayload(false, false, false, false));
            difficultyEditable=false;difficultyPending=ClientPlayNetworking.canSend(DifficultyPayload.ID);
            if(difficultyPending)ClientPlayNetworking.send(new DifficultyPayload(false,0,false,false));
        } else {
            destruction = HeliConfig.INSTANCE.explosionBlockDamage; editable = true; pending = false;
            mode=HeliConfig.INSTANCE.difficulty.ordinal();difficultyEditable=true;difficultyPending=false;
            status = Text.translatable("screen.no_24_heli.local");
        }
        refresh();
    }
    private void change() {
        if (!editable || pending) return;
        if (client.world != null) {
            pending = true; refresh();
            ClientPlayNetworking.send(new SettingsPayload(true, !destruction, false, false));
        } else {
            boolean old = HeliConfig.INSTANCE.explosionBlockDamage;
            HeliConfig.INSTANCE.explosionBlockDamage = !destruction;
            if (HeliConfig.save()) destruction = !destruction;
            else { HeliConfig.INSTANCE.explosionBlockDamage = old; status = Text.translatable("screen.no_24_heli.save_failed"); }
            refresh();
        }
    }
    /** 难度和破坏开关独立发送，避免覆盖其他管理员刚修改的配置。 */
    private void changeDifficulty(){
        if(!difficultyEditable||difficultyPending)return;
        if(client.world!=null){difficultyPending=true;refresh();ClientPlayNetworking.send(new DifficultyPayload(true,(mode+1)%3,false,false));}
        else{
            var old=HeliConfig.INSTANCE.difficulty;
            HeliConfig.INSTANCE.difficulty=HeliConfig.Difficulty.values()[(mode+1)%3];
            if(HeliConfig.save())mode=HeliConfig.INSTANCE.difficulty.ordinal();
            else{HeliConfig.INSTANCE.difficulty=old;status=Text.translatable("screen.no_24_heli.save_failed");}
            refresh();
        }
    }
    public void receiveDifficulty(DifficultyPayload packet){
        mode=Math.clamp(packet.mode(),0,2);difficultyEditable=packet.editable();difficultyPending=false;
        status=Text.translatable(!difficultyEditable?"screen.no_24_heli.readonly":packet.saved()?"screen.no_24_heli.personal_saved":"screen.no_24_heli.save_failed");
        refresh();
    }
    /** 仅使用服务端返回值更新界面，避免无权限客户端显示假设置。 */
    public void receive(SettingsPayload packet) {
        destruction = packet.destruction(); editable = packet.editable(); pending = false;
        status = Text.translatable(!editable ? "screen.no_24_heli.readonly" : packet.saved()
                ? "screen.no_24_heli.saved" : "screen.no_24_heli.save_failed");
        refresh();
    }
    private void refresh() {
        destructionButton.setMessage(Text.translatable("screen.no_24_heli.destruction", Text.translatable(destruction ? "options.on" : "options.off")));
        destructionButton.active = editable && !pending;
        difficultyButton.setMessage(Text.translatable("screen.no_24_heli.mode",Text.translatable("mode.no_24_heli."+mode)));
        difficultyButton.active=difficultyEditable&&!difficultyPending;
    }
    @Override public void close() { client.setScreen(parent); }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Screen.render 已绘制模糊背景，文字必须在其后绘制。
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 68, 0xffffff);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.no_24_heli.explanation"), width / 2, height / 2 + 24, 0xb8cbd0);
        context.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.no_24_heli.difficulty_hint"),width/2,height/2+40,0xb8cbd0);
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height / 2 + 58, 0xffcc66);
    }
}
