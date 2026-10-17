/** 文件：直升机机舱对原版雨雪幕的局部遮挡；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.mixin.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：补充实体机舱屋顶，防止只读取方块高度图的雨幕穿入机内，保留窗外雨雪。
 * 引用：net.minecraft.client.render.WorldRenderer#renderWeather 的高度查询；Box#raycast 求倾斜舱体上边界。
 * 生命周期：每次天气绘制前快照附近完好机舱，逐雨柱限制下端高度；只在客户端渲染线程运行。
 * 排查：漏雨检查注入和机体插值；窗外雨消失检查局部范围；本类不改天气、方块或光影配置。
 */
@Environment(EnvType.CLIENT)
@Mixin(WorldRenderer.class)
public abstract class HeliWeatherMixin {
    /** 雨柱宽一格，水平额外覆盖半对角线，避免斜向雨幕边缘伸入机舱；单位：机体局部格数。 */
    @Unique private static final Box HELI_RAIN_CABIN=new Box(-1.05,.55,-1.15,1.05,2.10,1.75).expand(.71,0,.71);
    /** 单帧内复用机体变换；不引用跨世界实体，也不逐雨柱扫描世界。 */
    @Unique private final List<HeliRainCover> heliRainCovers=new ArrayList<>();

    /** 缓存插值后的机体位置、旋转和逆旋转，雨柱沿世界竖直方向投射，不跟随机身倾斜。 */
    public record HeliRainCover(Vec3d position,Quaternionf rotation,Quaternionf inverse){}

    /** 功能：天气绘制前收集近处机舱；引用 MinecraftClient#world，只读本客户端可见的实体和插值。 */
    @Inject(method="renderWeather",at=@At("HEAD"))
    private void heliPrepareRainCovers(LightmapTextureManager lightmap,float delta,double cameraX,double cameraY,double cameraZ,CallbackInfo ci){
        heliRainCovers.clear();var world=MinecraftClient.getInstance().world;
        if(world==null||world.getRainGradient(delta)<=0)return;
        Vec3d camera=new Vec3d(cameraX,cameraY,cameraZ);
        for(var entity:world.getEntities())if(entity instanceof HelicopterEntity heli&&!heli.flight.wreck
                &&heli.getPos().squaredDistanceTo(camera)<32*32){
            Quaternionf rotation=HeliView.bodyRotation(heli,delta);
            heliRainCovers.add(new HeliRainCover(heli.getLerpedPos(delta),rotation,new Quaternionf(rotation).conjugate()));
        }
    }

    /**
     * 功能：仅在雨柱与完整机舱相交时抬高雨幕底端；引用 World#getTopY 保留原方块屋顶遮挡。
     * 返回值只供天气网格使用，不写真实高度图；整格取上界避免原版整数雨柱向舱内延伸。
     */
    @Redirect(method="renderWeather",at=@At(value="INVOKE",target="Lnet/minecraft/world/World;getTopY(Lnet/minecraft/world/Heightmap$Type;II)I"))
    private int heliRainTop(World world,Heightmap.Type type,int blockX,int blockZ){
        int height=world.getTopY(type,blockX,blockZ);
        for(var cover:heliRainCovers){
            float relativeX=(float)(blockX+.5-cover.position().x),relativeZ=(float)(blockZ+.5-cover.position().z);
            Vector3f start=new Vector3f(relativeX,8,relativeZ).rotate(cover.inverse());
            Vector3f end=new Vector3f(relativeX,-8,relativeZ).rotate(cover.inverse());
            var hit=HELI_RAIN_CABIN.raycast(new Vec3d(start.x,start.y,start.z),new Vec3d(end.x,end.y,end.z));
            if(hit.isPresent()){
                Vec3d local=hit.get();Vector3f roof=new Vector3f((float)local.x,(float)local.y,(float)local.z).rotate(cover.rotation());
                height=Math.max(height,(int)Math.ceil(cover.position().y+roof.y));
            }
        }
        return height;
    }
}
