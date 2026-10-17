/** 文件：停机坪动态灯芯渲染；端侧：CLIENT；方块实体：no_24_heli:helipad。 */
package org.DJB.no_24_heli.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.DJB.no_24_heli.block.HelipadBlockEntity;

/**
 * 职责：只绘制十六盏灯的缓存灯芯，圆盘、H、黄环和灯座由 HelipadModel 进入区块缓存。
 * 引用：net.minecraft.client.render.block.entity.BlockEntityRenderer 提供昼夜灯光入口；
 * net.minecraft.client.render.RenderLayer 保留白天受光、夜晚信标自发光路径。
 * 生命周期：客户端注册后逐帧调用，几何由资源加载阶段创建；仅客户端渲染线程读取世界。
 * 排查：静态圆盘缺面查 HelipadModel；昼夜不切换查同步太阳角；紫黑灯芯查资源重载后的图集。
 */
@Environment(EnvType.CLIENT)
public final class HelipadRenderer implements BlockEntityRenderer<HelipadBlockEntity> {
    /** 灯芯白天为暗琥珀色，夜间为暖黄色；独立于静态灯座。 */
    private static final int LENS_OFF_COLOR=0xff71613a,LENS_ON_COLOR=0xffffd477;

    /** 功能：由原版方块实体工厂创建渲染器；端侧：客户端初始化；参数为工厂上下文；无额外状态。 */
    public HelipadRenderer(BlockEntityRendererFactory.Context context){}

    /**
     * 功能：灯具跟随已加载地形视距，无灯碎片跳过方块实体绘制；引用 MC API：BlockEntityRenderer 距离筛选。
     * 端侧：客户端渲染线程；参数为方块实体与相机位置；返回：本格是否有动态灯芯；不加载区块。
     */
    @Override public boolean isInRenderDistance(HelipadBlockEntity entity,Vec3d cameraPos){
        return !HelipadModel.lenses(entity.getCachedState()).isEmpty();
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
     * 功能：读取缓存灯芯并按昼夜选择材质；引用 MC API：VertexConsumerProvider 提交原版实体或信标图层。
     * 端侧：客户端渲染线程；参数为方块实体与本帧绘制环境；副作用：只提交灯芯，不重建静态几何。
     * 排查：无灯碎片在查询光照、获取缓冲前退出；切换图层后不复用旧消费者。
     */
    @Override public void render(HelipadBlockEntity entity,float delta,MatrixStack matrices,VertexConsumerProvider consumers,int light,int overlay){
        var quads=HelipadModel.lenses(entity.getCachedState());
        if(quads.isEmpty())return;
        World world=entity.getWorld();
        boolean on=lampsOn(world,delta);
        int color=on?LENS_ON_COLOR:LENS_OFF_COLOR;
        int lensLight=on?LightmapTextureManager.MAX_LIGHT_COORDINATE:
                maximumLight(light,WorldRenderer.getLightmapCoordinates(world,entity.getPos().up()));
        var buffer=consumers.getBuffer(on?RenderLayer.getBeaconBeam(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,false):
                RenderLayer.getEntityCutout(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE));
        float red=(color>>16&255)/255f,green=(color>>8&255)/255f,blue=(color&255)/255f;
        for(var quad:quads)buffer.quad(matrices.peek(),quad,red,green,blue,1,lensLight,overlay);
    }

    /** 功能：按 net.minecraft.client.render.LightmapTextureManager 的天空光与方块光分别取强值；客户端调用，无副作用。 */
    private static int maximumLight(int first,int second){
        return Math.max(first&0xffff,second&0xffff)|Math.max(first>>>16,second>>>16)<<16;
    }
}
