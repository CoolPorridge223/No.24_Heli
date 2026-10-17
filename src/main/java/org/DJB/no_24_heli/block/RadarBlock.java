package org.DJB.no_24_heli.block;

import org.DJB.no_24_heli.data.AirspaceState;
import org.DJB.no_24_heli.network.RadarPayload;


import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 放置即创建 64 格禁飞区；右键由服务端打开设置，拆除/爆炸自动移除对应范围。 */
public final class RadarBlock extends Block {
    public RadarBlock(Settings settings){super(settings);}
    /** 非整块外形与模型底座一致；不再把下方方块的整个顶面剔除。 */
    @Override protected net.minecraft.util.shape.VoxelShape getOutlineShape(BlockState state,net.minecraft.world.BlockView world,BlockPos pos,net.minecraft.block.ShapeContext context){
        return net.minecraft.util.shape.VoxelShapes.union(
            Block.createCuboidShape(1,0,1,15,3,15),Block.createCuboidShape(6,3,6,10,10,10),
            Block.createCuboidShape(0,10,1,16,15,15),Block.createCuboidShape(7,15,7,9,16,9));
    }
    @Override public void onPlaced(World world,BlockPos pos,BlockState state,LivingEntity placer,ItemStack stack){
        super.onPlaced(world,pos,state,placer,stack);
        if(world instanceof ServerWorld sw&&placer!=null)AirspaceState.get(sw).put(pos,64,placer.getUuid());
    }
    @Override protected ActionResult onUse(BlockState state,World world,BlockPos pos,PlayerEntity player,BlockHitResult hit){
        if(world instanceof ServerWorld sw&&player instanceof ServerPlayerEntity p){
            AirspaceState zones=AirspaceState.get(sw);var zone=zones.at(pos);
            // 指令放置或旧世界尚无记录的雷达由首次使用者认领。
            if(zone==null){zones.put(pos,64,p.getUuid());zone=zones.at(pos);}
            ServerPlayNetworking.send(p,new RadarPayload(pos,zone.radius(),false,zone.owner().equals(p.getUuid())||p.hasPermissionLevel(2),true));
        }
        return ActionResult.SUCCESS;
    }
    @Override protected void onStateReplaced(BlockState state,World world,BlockPos pos,BlockState next,boolean moved){
        if(!state.isOf(next.getBlock())&&world instanceof ServerWorld sw)AirspaceState.get(sw).remove(pos);
        super.onStateReplaced(state,world,pos,next,moved);
    }
}
