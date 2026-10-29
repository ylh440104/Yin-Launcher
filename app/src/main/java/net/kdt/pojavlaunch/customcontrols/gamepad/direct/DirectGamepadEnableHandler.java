package net.kdt.pojavlaunch.customcontrols.gamepad.direct;

/**
 * 手柄直连模式的启用回调。
 *
 * CallbackBridge 在检测到物理手柄时会经由这个接口通知 UI 层切换输入模式。
 * 本项目暂未实现手柄直连 UI，保留接口以满足 GLFW 的调用约定。
 */
public interface DirectGamepadEnableHandler {
    void onDirectGamepadEnabled();
}