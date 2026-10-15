package org.DJB.no_24_heli;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockWithEntity;

/** 停机坪的承重碎片，偏移属性指向唯一中心，不注册可刷出的碎片物品。 */
public final class HelipadSurfaceBlock extends HelipadBlock {
    public static final MapCodec<HelipadSurfaceBlock> CODEC=createCodec(HelipadSurfaceBlock::new);
    public HelipadSurfaceBlock(Settings settings){super(settings);}
    @Override protected MapCodec<? extends BlockWithEntity> getCodec(){return CODEC;}
    @Override protected boolean isCenter(){return false;}
}
