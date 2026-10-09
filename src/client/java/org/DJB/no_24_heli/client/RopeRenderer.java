package org.DJB.no_24_heli.client;

import net.minecraft.client.render.entity.*;
import net.minecraft.util.Identifier;
import org.DJB.no_24_heli.*;

/** 绳降载体不可见，机上的可见绳索由直升机渲染器绘制。 */
public class RopeRenderer extends EntityRenderer<RopeSeatEntity>{
    public RopeRenderer(EntityRendererFactory.Context context){super(context);}
    @Override public Identifier getTexture(RopeSeatEntity e){return No_24_heli.id("textures/entity/heli_0.png");}
}
