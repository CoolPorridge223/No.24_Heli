package org.DJB.no_24_heli;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.text.Text;

/** 原子检查十格圆盘的放置空间，失败时保留物品与周围方块。 */
public final class HelipadItem extends BlockItem {
    public HelipadItem(Block block,Settings settings){super(block,settings);}
    @Override protected BlockState getPlacementState(ItemPlacementContext context){
        if(!HelipadBlock.canBuild(context.getWorld(),context.getBlockPos())){
            boolean restricted=context.getWorld() instanceof net.minecraft.server.world.ServerWorld world
                    &&HelipadBlock.isRestricted(world,context.getBlockPos());
            if(context.getPlayer()!=null)context.getPlayer().sendMessage(Text.translatable(
                    restricted?"message.no_24_heli.pad_restricted":"message.no_24_heli.pad_space"),true);
            return null;
        }
        return super.getPlacementState(context);
    }
}
