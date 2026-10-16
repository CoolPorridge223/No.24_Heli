package org.DJB.no_24_heli;

import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import java.util.*;

/** 停机坪接受机身触发多部件组装；库存先检查再一次性扣除。 */
public class HelipadBlock extends BlockWithEntity {
    /** 圆盘直径十格、厚四像素，碎片偏移用状态保存，不依赖额外存档表。 */
    public static final double RADIUS=5,THICKNESS=.25;
    public static final net.minecraft.state.property.IntProperty OFFSET_X=net.minecraft.state.property.IntProperty.of("offset_x",0,10),OFFSET_Z=net.minecraft.state.property.IntProperty.of("offset_z",0,10);
    private static final net.minecraft.util.shape.VoxelShape[][] SHAPES=new net.minecraft.util.shape.VoxelShape[11][11];
    public static final com.mojang.serialization.MapCodec<HelipadBlock> CODEC=createCodec(HelipadBlock::new);
    @Override protected com.mojang.serialization.MapCodec<? extends BlockWithEntity> getCodec(){return CODEC;}
    protected boolean isCenter(){return true;}
    /** 创造模式选取圆盘任意位置，均返回完整停机坪物品。 */
    @Override public ItemStack getPickStack(net.minecraft.world.WorldView world,BlockPos pos,BlockState state){return new ItemStack(No_24_heli.HELIPAD_ITEM);}
    @Override protected void appendProperties(net.minecraft.state.StateManager.Builder<Block,BlockState> builder){builder.add(OFFSET_X,OFFSET_Z);}
    @Override public net.minecraft.block.entity.BlockEntity createBlockEntity(BlockPos pos,BlockState state){return new HelipadBlockEntity(pos,state);}
    @Override protected BlockRenderType getRenderType(BlockState state){return BlockRenderType.ENTITYBLOCK_ANIMATED;}
    /** 圆周与方格相交才占用该位置，不使用十乘十的方形碰撞面。 */
    public static boolean covers(int x,int z){return Math.hypot(Math.max(0,Math.abs(x)-.5),Math.max(0,Math.abs(z)-.5))<RADIUS;}
    public static BlockPos center(BlockPos pos,BlockState state){return pos.add(5-state.get(OFFSET_X),0,5-state.get(OFFSET_Z));}
    /** 每格细分十六条，圆盘边缘碰撞始终留在半径以内。 */
    @Override protected net.minecraft.util.shape.VoxelShape getOutlineShape(BlockState state,net.minecraft.world.BlockView world,BlockPos pos,ShapeContext context){
        int dx=state.get(OFFSET_X)-5,dz=state.get(OFFSET_Z)-5;
        var shape=SHAPES[dx+5][dz+5];if(shape!=null)return shape;
        shape=net.minecraft.util.shape.VoxelShapes.empty();
        for(int i=0;i<16;i++){
            double z0=dz-.5+i/16.0,z1=z0+1/16.0,edge=Math.max(Math.abs(z0),Math.abs(z1));
            if(edge>=RADIUS)continue;
            double half=Math.sqrt(RADIUS*RADIUS-edge*edge),x0=Math.max(dx-.5,-half),x1=Math.min(dx+.5,half);
            if(x1>x0)shape=net.minecraft.util.shape.VoxelShapes.union(shape,net.minecraft.util.shape.VoxelShapes.cuboid(x0-dx+.5,0,i/16.0,x1-dx+.5,THICKNESS,(i+1)/16.0));
        }
        return SHAPES[dx+5][dz+5]=shape.simplify();
    }
    /**
     * 检查停机坪圆盘与真实禁飞圆是否相交或相切；坐标均以方块中心为准，高度不影响禁飞限制。
     * 客户端地图和服务端放置、导航共用同一几何规则，不使用规划时扩张的安全圈。
     */
    public static boolean intersectsZone(BlockPos pad,BlockPos zone,int radius){
        return Math.hypot((double)pad.getX()-zone.getX(),(double)pad.getZ()-zone.getZ())<=radius+RADIUS;
    }
    /** 仅读取 net.minecraft.server.world.ServerWorld 的真实禁区记录，不加载远处区块。 */
    public static boolean isRestricted(ServerWorld world,BlockPos pos){
        return AirspaceState.get(world).zones().stream().anyMatch(zone->intersectsZone(pos,zone.pos(),zone.radius()));
    }
    /**
     * 拦截落在受限旧停机坪上的普通地图标点，防止绕过停机坪按钮的禁用状态。
     * 已放置后新覆盖禁区的圆盘也适用；仅服务端主线程查询已加载中心。
     */
    public static boolean isRestrictedTarget(ServerWorld world,double x,double z){
        for(BlockPos pos:HelipadBlockEntity.centers(world))
            if(Math.hypot(x-pos.getX()-.5,z-pos.getZ()-.5)<=RADIUS&&isRestricted(world,pos))return true;
        return false;
    }
    /** 放置前检查整个圆盘区域；禁区中不放置，不覆盖其他方块、未加载区块或世界边界。 */
    public static boolean canBuild(World world,BlockPos pos){
        if(world instanceof ServerWorld serverWorld&&isRestricted(serverWorld,pos))return false;
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)if(covers(x,z)){
            BlockPos p=pos.add(x,0,z);
            if(!world.isChunkLoaded(p)||!world.getWorldBorder().contains(p))return false;
            if(x==0&&z==0)continue;
            if(!world.getBlockState(p).isReplaceable())return false;
        }
        return true;
    }
    /** 一次放置生成整个圆盘的可承重碎片，只消耗一个停机坪物品。 */
    @Override public void onPlaced(World world,BlockPos pos,BlockState state,net.minecraft.entity.LivingEntity placer,ItemStack stack){
        super.onPlaced(world,pos,state,placer,stack);if(world.isClient||!isCenter())return;
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)if((x!=0||z!=0)&&covers(x,z))
            world.setBlockState(pos.add(x,0,z),No_24_heli.HELIPAD_SURFACE.getDefaultState().with(OFFSET_X,x+5).with(OFFSET_Z,z+5),Block.NOTIFY_ALL);
    }
    /** 拆除任意碎片都拆除整个结构，碎片本身没有掉落，中心只掉落一个物品。 */
    @Override protected void onStateReplaced(BlockState state,World world,BlockPos pos,BlockState replacement,boolean moved){
        if(!world.isClient&&!state.isOf(replacement.getBlock())){
            BlockPos origin=center(pos,state);
            if(isCenter()){
                for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)if(x!=0||z!=0){
                    BlockPos p=origin.add(x,0,z);BlockState part=world.getBlockState(p);
                    if(part.isOf(No_24_heli.HELIPAD_SURFACE)&&center(p,part).equals(origin))world.removeBlock(p,false);
                }
            }else if(world.getBlockState(origin).isOf(No_24_heli.HELIPAD))world.breakBlock(origin,true);
        }
        super.onStateReplaced(state,world,pos,replacement,moved);
    }
    /** 支持薄平台高度和整个圆盘上的原有停机补给行为。 */
    public static boolean supports(World world,BlockPos pos){
        var state=world.getBlockState(pos);return state.isOf(No_24_heli.HELIPAD)||state.isOf(No_24_heli.HELIPAD_SURFACE);
    }

    public HelipadBlock(Settings settings){super(settings);setDefaultState(getStateManager().getDefaultState().with(OFFSET_X,5).with(OFFSET_Z,5));}
    @Override protected ItemActionResult onUseWithItem(ItemStack stack,BlockState state,World world,BlockPos pos,PlayerEntity player,Hand hand,BlockHitResult hit){
        if(!isCenter()){
            BlockPos origin=center(pos,state);BlockState pad=world.getBlockState(origin);
            return pad.isOf(No_24_heli.HELIPAD)?((HelipadBlock)No_24_heli.HELIPAD).onUseWithItem(stack,pad,world,origin,player,hand,hit):ItemActionResult.FAIL;
        }
        if(!stack.isOf(No_24_heli.FUSELAGE))return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if(world.isClient)return ItemActionResult.SUCCESS;
        Item[] parts={No_24_heli.FUSELAGE,No_24_heli.ROTOR,No_24_heli.TAIL,No_24_heli.ENGINE,No_24_heli.TANK};
        if(!player.isCreative())for(Item part:parts)if(player.getInventory().count(part)<1){player.sendMessage(Text.translatable("message.no_24_heli.parts_missing"),true);return ItemActionResult.FAIL;}
        HelicopterEntity heli=new HelicopterEntity(No_24_heli.HELI,world);
        heli.refreshPositionAndAngles(pos.getX()+.5,pos.getY()+THICKNESS+.01,pos.getZ()+.5,player.getYaw(),0);heli.flight.yaw=player.getYaw();
        if(!world.isSpaceEmpty(heli,heli.getBoundingBox()))return ItemActionResult.FAIL;
        if(!world.spawnEntity(heli))return ItemActionResult.FAIL;
        if(!player.isCreative())for(Item part:parts){for(int i=0;i<player.getInventory().size();i++){var slot=player.getInventory().getStack(i);if(slot.isOf(part)){slot.decrement(1);break;}}}
        player.sendMessage(Text.translatable("message.no_24_heli.assembled"),true);return ItemActionResult.SUCCESS;
    }
}
