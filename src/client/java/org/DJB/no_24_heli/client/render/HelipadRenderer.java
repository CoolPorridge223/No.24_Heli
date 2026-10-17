/** 文件：停机坪动态灯芯渲染；端侧：CLIENT；方块实体：no_24_heli:helipad。 */
package org.DJB.no_24_heli.client.render;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.DJB.no_24_heli.block.HelipadBlockEntity;
import org.DJB.no_24_heli.block.HelipadBlock;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：将同一圆盘的灯芯按光照合并，只由一个已加载带灯碎片绘制；静态圆盘仍使用区块缓存。
 * 引用：net.minecraft.client.render.block.entity.BlockEntityRenderer 提供昼夜灯光入口；
 * net.minecraft.client.render.RenderLayer 保留白天受光、夜晚信标自发光路径。
 * 生命周期：客户端注册后逐帧调用，世界与各灯采光每 tick 读取一次；仅客户端渲染线程访问。
 * 排查：静态圆盘缺面查 HelipadModel；昼夜不切换查同步太阳角；紫黑灯芯查资源重载后的图集。
 */
@Environment(EnvType.CLIENT)
public final class HelipadRenderer implements BlockEntityRenderer<HelipadBlockEntity> {
    /** 已加载圆盘的本 tick 光照快照，中心位置仅作客户端缓存键，不参与地图或服务器索引。 */
    private final Map<BlockPos,Pad> pads=new HashMap<>();
    /** 弱引用不阻止断开后的世界释放；换世界时立即清空位置缓存。 */
    private WeakReference<World> cachedWorld=new WeakReference<>(null);
    /** 世界年龄单位为 tick，不使用可被时间命令跳转的日时；每 tick 仅判断一次昼夜。 */
    private long cachedTick=Long.MIN_VALUE;
    /** 本 tick 的昼夜灯具状态，不写入方块状态或同步数据。 */
    private boolean on;
    /** 一个有效碎片负责整圈，groups 按打包光照坐标保存灯具位掩码。 */
    private record Pad(long tick,BlockPos owner,Map<Integer,Integer> groups){}

    /** 功能：由原版方块实体工厂创建渲染器；端侧：客户端初始化；参数为工厂上下文；无额外状态。 */
    public HelipadRenderer(BlockEntityRendererFactory.Context context){}

    /**
     * 功能：从实际已加载灯具中选唯一绘制者，跟随地形视距；引用 MC API：BlockEntityRenderer 距离筛选。
     * 端侧：客户端渲染线程；返回：当前碎片是否负责合并灯芯；不查询或加载未加载区块。
     */
    @Override public boolean isInRenderDistance(HelipadBlockEntity entity,Vec3d cameraPos){
        return !HelipadModel.lenses(entity.getCachedState()).isEmpty()&&
                entity.getPos().equals(pad(entity,0).owner)&&
                HelipadMesh.visible(HelipadBlock.center(entity.getPos(),entity.getCachedState()));
    }

    /**
     * 功能：整圈灯超出负责碎片的一格包围盒，保留所有已加载碎片的显示；引用 BlockEntityRenderer 的区块裁剪扩展。
     * 端侧：客户端；带灯碎片返回 true，整圈视锥由 HelipadMesh 筛选，范围仍限于已加载地形。
     */
    @Override public boolean rendersOutsideBoundingBox(HelipadBlockEntity entity){
        return !HelipadModel.lenses(entity.getCachedState()).isEmpty();
    }

    /**
     * 功能：每 tick 检查整圈碎片及其环境光，合并同光照的灯具；只访问已加载区块。
     * 引用 MC API：World#getTime 为世界年龄，WorldRenderer#getLightmapCoordinates 延续原灯芯上下格采光。
     * 端侧：客户端渲染线程；副作用：更新短期快照；跨区块时选剩余有效碎片，不依赖中心区块仍在内存。
     */
    private Pad pad(HelipadBlockEntity entity,float delta){
        World world=entity.getWorld();long tick=world.getTime();
        if(cachedWorld.get()!=world){pads.clear();cachedWorld=new WeakReference<>(world);cachedTick=Long.MIN_VALUE;}
        if(cachedTick!=tick){
            cachedTick=tick;on=lampsOn(world,delta);
            // 每秒清理上一轮未再访问的已卸载圆盘，缓存不随探索世界持续增长。
            if(tick%20==0)pads.values().removeIf(pad->pad.tick<tick-1);
        }
        BlockPos center=HelipadBlock.center(entity.getPos(),entity.getCachedState());Pad result=pads.get(center);
        if(result!=null&&result.tick==tick)return result;
        Map<Integer,Integer> groups=new LinkedHashMap<>();BlockPos owner=null;var cells=HelipadModel.lampCells();
        for(int i=0;i<cells.size();i++){
            BlockPos pos=center.add(cells.get(i));if(!world.isChunkLoaded(pos))continue;
            var state=world.getBlockState(pos);
            if(!(state.isOf(No_24_heli.HELIPAD)||state.isOf(No_24_heli.HELIPAD_SURFACE))||
                    !HelipadBlock.center(pos,state).equals(center))continue;
            if(owner==null)owner=pos;
            int light=on?LightmapTextureManager.MAX_LIGHT_COORDINATE:maximumLight(
                    WorldRenderer.getLightmapCoordinates(world,pos),WorldRenderer.getLightmapCoordinates(world,pos.up()));
            groups.merge(light,1<<i,(first,second)->first|second);
        }
        result=new Pad(tick,owner,groups);pads.put(center,result);return result;
    }

    /**
     * 功能：依据当前同步的太阳角度与天气，实时判断指示灯是否应当开启。
     * net.minecraft.world.World#isNight 读取缓存环境暗度，但
     * net.minecraft.client.world.ClientWorld 只在构造时计算该缓存，不能用于客户端动态开关。
     * 这里沿用 net.minecraft.world.World#calculateAmbientDarkness 的公式和 isNight 的四级阈值，
     * 使用 getSkyAngle、getRainGradient、getThunderGradient 的实时值；固定时间维度仍关闭。
     * 端侧/线程：仅客户端渲染线程；仅读取世界，不修改缓存暗度或其他渲染的光照。
     * 故障排查：时间命令、睡觉或自然入夜后不切换时，检查世界时间同步，不能退回缓存判定。
     */
    private static boolean lampsOn(World world,float delta){
        if(world.getDimension().hasFixedTime())return false;
        // 原版太阳光强为 0.5 + 2 × 限制到 [-0.25,0.25] 的太阳高度余弦，范围 0～1。
        double sunlight=.5+2*MathHelper.clamp((double)MathHelper.cos(
                world.getSkyAngle(delta)*(float)(Math.PI*2)),-.25,.25);
        // 原版雨、雷各使太阳光最多衰减 5/16；暗度以 0～11 表示，四级开始视为夜晚。
        double rain=1-(double)(world.getRainGradient(delta)*5)/16;
        double thunder=1-(double)(world.getThunderGradient(delta)*5)/16;
        return (int)((1-sunlight*rain*thunder)*11)>=4;
    }

    /**
     * 功能：由唯一有效碎片绘制整圈缓存灯芯；引用 MC API：MatrixStack 平移到圆盘中心，GPU 只更新矩阵。
     * 端侧：客户端渲染线程；副作用：惰性上传共享静态网格；可选光影接口不匹配时仍批量提交原灯芯。
     * 排查：不得开始或结束 Iris 的实体阶段，此处仍处于其方块实体阶段；材质由原图层负责。
     */
    @Override public void render(HelipadBlockEntity entity,float delta,MatrixStack matrices,VertexConsumerProvider consumers,int light,int overlay){
        Pad pad=pad(entity,delta);if(!entity.getPos().equals(pad.owner))return;
        var state=entity.getCachedState();matrices.push();
        try{
            matrices.translate(5-state.get(HelipadBlock.OFFSET_X),0,5-state.get(HelipadBlock.OFFSET_Z));
            boolean gpu=HelipadMesh.prepare();int color=on?HelipadMesh.ON_COLOR:HelipadMesh.OFF_COLOR;
            for(var group:pad.groups.entrySet()){
                if(gpu){HelipadMesh.draw(group.getValue(),on,group.getKey(),matrices);continue;}
                var buffer=consumers.getBuffer(HelipadMesh.layer(on));var cells=HelipadModel.lampCells();
                for(int i=0;i<cells.size();i++)if((group.getValue()&(1<<i))!=0){
                    var offset=cells.get(i);matrices.push();matrices.translate(offset.getX(),0,offset.getZ());
                    for(var quad:HelipadModel.lenses(offset))buffer.quad(matrices.peek(),quad,
                            (color>>16&255)/255f,(color>>8&255)/255f,(color&255)/255f,1,group.getKey(),overlay);
                    matrices.pop();
                }
            }
        }finally{matrices.pop();}
    }

    /** 功能：按 net.minecraft.client.render.LightmapTextureManager 的天空光与方块光分别取强值；客户端调用，无副作用。 */
    private static int maximumLight(int first,int second){
        return Math.max(first&0xffff,second&0xffff)|Math.max(first>>>16,second>>>16)<<16;
    }
}
