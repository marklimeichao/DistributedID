package com.wolfbe.distributedid.sdks;

import com.wolfbe.distributedid.core.SnowFlake;
import com.wolfbe.distributedid.util.GlobalConfig;
import com.wolfbe.distributedid.util.NettyUtil;
import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 由于请求的协议很简单，只有5字符：getid
 * 解码时使用FixedLengthFrameDecoder即可
 *
 * @author Andy
 */
public class SdkServerHandler extends SimpleChannelInboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(SdkServerHandler.class);
    /**
     * 通过信号量来控制流量
     */
    private Semaphore semaphore = new Semaphore(GlobalConfig.HANDLE_SDKS_TPS);
    private SnowFlake snowFlake;

    public SdkServerHandler(SnowFlake snowFlake) {
        this.snowFlake = snowFlake;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof SdkProto) {
            SdkProto sdkProto = (SdkProto) msg;
            logger.info("SdkServerHandler msg is: {}", sdkProto.toString());
            if (semaphore.tryAcquire(GlobalConfig.ACQUIRE_TIMEOUTMILLIS, TimeUnit.MILLISECONDS)) {
                try {
                    long did = snowFlake.nextId();
                    logger.info("SdkServerHandler did is: {}", did);
                    sdkProto.setDid(did);
                    ctx.channel().writeAndFlush(sdkProto).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture channelFuture) throws Exception {
                            semaphore.release();
                        }
                    });
                } catch (Exception e) {
                    semaphore.release();
                    logger.error("SdkServerhandler error", e);
                }
            } else {
                String info = String.format("SdkServerHandler tryAcquire semaphore timeout, %dms, waiting thread " +
                                "nums: %d availablePermit: %d",     //
                        GlobalConfig.ACQUIRE_TIMEOUTMILLIS, //
                        this.semaphore.getQueueLength(),    //
                        this.semaphore.availablePermits()   //
                );
                logger.warn(info);
                throw new Exception(info);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Channel channel = ctx.channel();
        logger.error("SdkServerHandler channel [{}] error and will be closed", NettyUtil.parseRemoteAddr(channel), cause);
        NettyUtil.closeChannel(channel);
    }
}
