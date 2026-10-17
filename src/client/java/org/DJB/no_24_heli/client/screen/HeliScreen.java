package org.DJB.no_24_heli.client.screen;

import org.DJB.no_24_heli.screen.HeliScreenHandler;


import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** 燃油和货物使用真实容器槽位，支持原版拖动、分堆与 Shift 转移。 */
public class HeliScreen extends HandledScreen<HeliScreenHandler>{
    public HeliScreen(HeliScreenHandler handler,PlayerInventory player,Text title){super(handler,player,title);backgroundWidth=176;backgroundHeight=212;playerInventoryTitleY=118;}
    @Override protected void drawBackground(DrawContext ctx,float delta,int mx,int my){
        ctx.fill(x-2,y-2,x+178,y+214,0xff4f735d);ctx.fill(x,y,x+176,y+212,0xffd9dfd4);
        for(var slot:handler.slots){ctx.fill(x+slot.x-1,y+slot.y-1,x+slot.x+17,y+slot.y+17,0xff49554b);ctx.fill(x+slot.x,y+slot.y,x+slot.x+16,y+slot.y+16,0xffaab8a8);}
        ctx.fill(x+32,y+36,x+165,y+44,0xff59665e);
        int full=Math.max(1,handler.stats.get(1));int width=(int)(133L*handler.stats.get(0)/full);
        ctx.fill(x+32,y+36,x+32+width,y+44,0xff3b9360);
        ctx.drawText(textRenderer,Text.translatable("gui.no_24_heli.fuel",handler.stats.get(0),full),x+32,y+23,0xff203b2a,false);
        ctx.drawText(textRenderer,Text.translatable("gui.no_24_heli.parts",handler.stats.get(2),handler.stats.get(3),handler.stats.get(4),handler.stats.get(5)),x+8,y+58,0xff203b2a,false);
    }
    @Override public void render(DrawContext ctx,int mx,int my,float delta){super.render(ctx,mx,my,delta);drawMouseoverTooltip(ctx,mx,my);}
}
