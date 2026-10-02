// IUserService.aidl
package com.nexus.agent.userservice;

interface IUserService {
    void injectTap(float x, float y);
    void injectSwipe(float x1, float y1, float x2, float y2, int duration);
    void injectLongPress(float x, float y, int duration);
    void injectDrag(float fromX, float fromY, float toX, float toY, int duration);
    // 多指手势：每项为 JSON 字符串 {"x":..,"y":..,"action":"down/up/move"}
    void injectMultiTouch(in List<String> points);
    String execCommand(String command);
}
