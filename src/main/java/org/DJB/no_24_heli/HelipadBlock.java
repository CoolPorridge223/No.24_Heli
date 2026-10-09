package org.DJB.no_24_heli;

import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import java.util.*;

/** 停机坪接受机身触发多部件组装；库存先检查再一次性扣除。 */
public class HelipadBlock extends Block {
    public HelipadBlock(Settings settings){super(settings);}
    @Override protected ItemActionResult onUseWithItem(ItemStack stack,BlockState state,World world,BlockPos pos,PlayerEntity player,Hand hand,BlockHitResult hit){
        if(!stack.isOf(No_24_heli.FUSELAGE))return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if(world.isClient)return ItemActionResult.SUCCESS;
        Item[] parts={No_24_heli.FUSELAGE,No_24_heli.ROTOR,No_24_heli.TAIL,No_24_heli.ENGINE,No_24_heli.TANK};
        if(!player.isCreative())for(Item part:parts)if(player.getInventory().count(part)<1){player.sendMessage(Text.translatable("message.no_24_heli.parts_missing"),true);return ItemActionResult.FAIL;}
        HelicopterEntity heli=new HelicopterEntity(No_24_heli.HELI,world);
        heli.refreshPositionAndAngles(pos.getX()+.5,pos.getY()+1.01,pos.getZ()+.5,player.getYaw(),0);heli.flight.yaw=player.getYaw();
        if(!world.isSpaceEmpty(heli,heli.getBoundingBox()))return ItemActionResult.FAIL;
        if(!world.spawnEntity(heli))return ItemActionResult.FAIL;
        if(!player.isCreative())for(Item part:parts){for(int i=0;i<player.getInventory().size();i++){var slot=player.getInventory().getStack(i);if(slot.isOf(part)){slot.decrement(1);break;}}}
        player.sendMessage(Text.translatable("message.no_24_heli.assembled"),true);return ItemActionResult.SUCCESS;
    }
}
