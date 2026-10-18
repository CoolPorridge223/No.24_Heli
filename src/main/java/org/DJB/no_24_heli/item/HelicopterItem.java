package org.DJB.no_24_heli.item;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.HelicopterCollision;

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
        // 顶部点击取真实命中面，停机坪与半砖不再按整格顶部悬空生成并砸向承重面。
        double y=ctx.getSide()==Direction.UP?ctx.getHitPos().y+.05:pos.getY()+.05;
        HelicopterEntity heli=new HelicopterEntity(No_24_heli.HELI,world);
        heli.refreshPositionAndAngles(pos.getX()+.5,y,pos.getZ()+.5,player.getYaw(),0);
        heli.restorePacked(ctx.getStack());heli.flight.yaw=player.getYaw();
        heli.setBoundingBox(HelicopterCollision.bounds(heli));
        if(!HelicopterCollision.isSpaceEmpty(heli,player))return ActionResult.FAIL;
        if(world.spawnEntity(heli)){if(!player.isCreative()||ctx.getStack().contains(net.minecraft.component.DataComponentTypes.CUSTOM_DATA))ctx.getStack().decrement(1);return ActionResult.CONSUME;}
        return ActionResult.FAIL;
    }
}
