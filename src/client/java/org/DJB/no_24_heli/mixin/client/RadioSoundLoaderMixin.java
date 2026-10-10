package org.DJB.no_24_heli.mixin.client;

import net.minecraft.client.sound.*;
import net.minecraft.util.Identifier;
import org.DJB.no_24_heli.client.*;
import org.DJB.no_24_heli.No_24_heli;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.*;

/**
 * 只接管模组电台的虚拟声音路径，普通OGG/原版声音完全沿用原加载器。
 * Minecraft流式声音使用.ogg后缀定位，这里把固定槽位转交MP3解码器；不需要假OGG文件。
 * 解码及接入时跳帧放在线程池，不能在渲染线程读取整首歌曲阻塞游戏。
 */
@Mixin(SoundLoader.class)
public class RadioSoundLoaderMixin {
    @Inject(method="loadStreamed",at=@At("HEAD"),cancellable=true)
    private void loadRadio(Identifier id,boolean repeat,CallbackInfoReturnable<CompletableFuture<AudioStream>> cir){
        if(!id.getNamespace().equals(No_24_heli.MOD_ID)||!id.getPath().startsWith("sounds/radio/"))return;
        String[] fields=id.getPath().substring("sounds/radio/".length()).replace(".ogg","").split("/");
        if(fields.length!=2)return;
        try{
            int slot=Integer.parseInt(fields[0]);long offset=Long.parseLong(fields[1]);
            if(slot<0||slot>=8||offset<0)return;
            cir.setReturnValue(CompletableFuture.supplyAsync(()->{
                try{return new Mp3AudioStream(()->RadioLibrary.open(slot),repeat,offset);}
                catch(java.io.IOException e){No_24_heli.LOGGER.warn("电台槽位 {} 的MP3无法播放",slot+1,e);throw new CompletionException(e);}
            }));
        }catch(NumberFormatException ignored){/* 只有本模组构造的合法路径才可进入MP3接口。 */}
    }
}
