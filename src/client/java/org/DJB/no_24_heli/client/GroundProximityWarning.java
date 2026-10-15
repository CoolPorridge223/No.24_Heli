package org.DJB.no_24_heli.client;

import net.minecraft.client.MinecraftClient;
import org.DJB.no_24_heli.HelicopterEntity;
import org.DJB.no_24_heli.No_24_heli;

/** 近地高度提示与下降率警告共用采样；语音通过事件接口接入，不循环播放警报。 */
public final class GroundProximityWarning {
    /** 档位从远到近排列，单位为米；零号表示没有低空提示。 */
    private static final int[] HEIGHTS={0,50,30,20,10,5};
    /** 各档危险下降率，单位为米/秒；越近地面容许下降率越小。 */
    private static final double[] SINK_LIMITS={0,5,4,3,2,1.5};
    private static final Monitor monitor=new Monitor();
    private static int vehicle=-1;
    /** warning 区分正常高度播报与危险下降率警告，actualHeight 为实际离地高度。 */
    public record Notice(int band,int threshold,double actualHeight,double verticalSpeed,boolean warning){}
    @FunctionalInterface public interface VoiceListener {void onNotice(Notice notice);}
    private static VoiceListener voiceListener=notice->{};
    private GroundProximityWarning(){}
    /** 客户端初始化时注册语音回调；null 恢复无声占位接口。 */
    public static void setVoiceListener(VoiceListener listener){voiceListener=listener==null?notice->{}:listener;}
    public static int level(){return monitor.level;}
    public static boolean warning(){return monitor.warning;}
    public static double height(){return monitor.height;}
    public static int band(double height){return height<=5?5:height<=10?4:height<=20?3:height<=30?2:height<=50?1:0;}
    /** 独立状态机便于边界测试；一米回差防止阈值附近抖动反复播报。 */
    public static final class Monitor {
        private int level,enteredMask,warnedMask;
        private boolean warning;
        private double height;
        public int level(){return level;}
        public boolean warning(){return warning;}
        public void reset(){level=enteredMask=warnedMask=0;warning=false;height=0;}
        /** 仅下降触发，同档普通播报和警告各至多一次，离开该档一米后重新允许。 */
        public Notice update(double height,double verticalSpeed,boolean active){
            if(!active||!Double.isFinite(height)||!Double.isFinite(verticalSpeed)){reset();return null;}
            this.height=height;level=band(height);
            for(int i=1;i<HEIGHTS.length;i++)if(height>HEIGHTS[i]+1){enteredMask&=~(1<<i);warnedMask&=~(1<<i);}
            warning=level>0&&verticalSpeed<-SINK_LIMITS[level];
            if(level==0||verticalSpeed>=-.1)return null;
            int bit=1<<level;
            boolean entry=(enteredMask&bit)==0,alarm=warning&&(warnedMask&bit)==0;
            if(!entry&&!alarm)return null;
            // 一次采样跳过多档时只播报当前最近一档，避免多条语音排队过时。
            enteredMask|=(1<<(level+1))-2;
            if(warning)warnedMask|=(1<<(level+1))-2;
            return new Notice(level,HEIGHTS[level],height,verticalSpeed,warning);
        }
    }
    public static void tick(MinecraftClient client){
        if(client.world==null||client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity h)){
            vehicle=-1;monitor.reset();return;
        }
        if(vehicle!=h.getId()){vehicle=h.getId();monitor.reset();}
        double height=h.heightAboveGround();
        Notice notice=monitor.update(height,h.flight.vy*20,!h.flight.wreck&&h.flight.armed&&!h.isOnGround()&&!h.isTouchingWater()&&height>.15);
        if(notice!=null)try{voiceListener.onNotice(notice);}catch(RuntimeException e){No_24_heli.LOGGER.warn("近地语音接口调用失败",e);}
    }
}
