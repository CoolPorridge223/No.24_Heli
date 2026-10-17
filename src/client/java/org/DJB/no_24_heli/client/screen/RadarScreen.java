package org.DJB.no_24_heli.client.screen;

import org.DJB.no_24_heli.data.AirspaceState;
import org.DJB.no_24_heli.network.RadarPayload;



import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;

/** 地面雷达半径编辑器。保存等待回执，普通访客只读，不先行显示虚假的成功。 */
public final class RadarScreen extends Screen {
    private RadarPayload state;
    private TextFieldWidget radius;
    private ButtonWidget save;
    private Text status=Text.empty();
    public RadarScreen(RadarPayload state){super(Text.translatable("screen.no_24_heli.radar"));this.state=state;}
    @Override protected void init(){
        radius=addDrawableChild(new TextFieldWidget(textRenderer,width/2-80,height/2-16,160,20,Text.translatable("screen.no_24_heli.radius")));
        radius.setMaxLength(3);radius.setText(Integer.toString(state.radius()));radius.setEditable(state.editable());
        radius.setChangedListener(value->refresh());
        save=addDrawableChild(ButtonWidget.builder(Text.translatable("screen.no_24_heli.apply"),b->{
            save.active=false;status=Text.translatable("screen.no_24_heli.loading");
            ClientPlayNetworking.send(new RadarPayload(state.pos(),Integer.parseInt(radius.getText()),true,false,false));
        }).dimensions(width/2-80,height/2+12,78,20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),b->close()).dimensions(width/2+2,height/2+12,78,20).build());refresh();
    }
    private void refresh(){
        if(save==null)return;
        int value;try{value=Integer.parseInt(radius.getText());}catch(NumberFormatException e){value=0;}
        save.active=state.editable()&&value>=AirspaceState.MIN_RADIUS&&value<=AirspaceState.MAX_RADIUS;
    }
    public void receive(RadarPayload result){
        if(!result.pos().equals(state.pos()))return;state=result;radius.setText(Integer.toString(result.radius()));
        status=Text.translatable(result.saved()?"screen.no_24_heli.saved":"screen.no_24_heli.save_failed");refresh();
    }
    @Override public boolean shouldPause(){return false;}
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        super.render(ctx,mx,my,delta);
        ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,height/2-60,0xffffff);
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.no_24_heli.radius"),width/2,height/2-35,0xbbddcc);
        ctx.drawCenteredTextWithShadow(textRenderer,state.editable()?status:Text.translatable("screen.no_24_heli.radar_readonly"),width/2,height/2+44,0xffcc66);
    }
}
