package moe.uni.agent.framework;

import android.view.SurfaceControl;

/**
 * 一块屏幕的只读镜像（ADR-0031 第 3 条）。surface 是 AgentFramework 持有的容器 leash，
 * 下面挂着窗口层和流光层的镜像；调用方把它 reparent 到自己 SurfaceView 的 SurfaceControl 下并设缩放。
 * 镜像不接收触摸。
 */
parcelable ScreenMirror {
    SurfaceControl surface;
    /** 被镜像屏幕的逻辑尺寸（像素），缩放按它计算。 */
    int width;
    int height;
}
