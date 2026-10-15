package org.DJB.no_24_heli;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/** 每格渲染圆盘的一部分，避免中心方块离开视野时整个平台消失。 */
public final class HelipadBlockEntity extends BlockEntity {
    public HelipadBlockEntity(BlockPos pos,BlockState state){super(No_24_heli.HELIPAD_ENTITY,pos,state);}
}
