package org.DJB.no_24_heli;

import net.minecraft.item.*;
import net.minecraft.util.*;
import net.minecraft.util.math.*;

/** 放置前检查整机碰撞体，避免在墙内生成载具。 */
public class HelicopterItem extends Item {
    public HelicopterItem(Settings settings){super(settings);}
    @Override public ActionResult useOnBlock(ItemUsageContext ctx) {
        var world=ctx.getWorld();var player=ctx.getPlayer();if(player==null)return ActionResult.PASS;
        if(world.isClient)return ActionResult.SUCCESS;
        var pos=ctx.getBlockPos().offset(ctx.getSide());
        HelicopterEntity heli=new HelicopterEntity(No_24_heli.HELI,world);
        heli.refreshPositionAndAngles(pos.getX()+.5,pos.getY()+.05,pos.getZ()+.5,player.getYaw(),0);
        if(!world.isSpaceEmpty(heli,heli.getBoundingBox()))return ActionResult.FAIL;
        heli.restorePacked(ctx.getStack());heli.flight.yaw=player.getYaw();
        if(world.spawnEntity(heli)){if(!player.isCreative()||ctx.getStack().contains(net.minecraft.component.DataComponentTypes.CUSTOM_DATA))ctx.getStack().decrement(1);return ActionResult.CONSUME;}
        return ActionResult.FAIL;
    }
}
