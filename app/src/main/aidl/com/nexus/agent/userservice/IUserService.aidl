// IUserService.aidl
package com.nexus.agent.userservice;

interface IUserService {
    boolean injectTap(float x, float y);
    boolean injectSwipe(float x1, float y1, float x2, float y2, int duration);
    boolean injectLongPress(float x, float y, int duration);
    boolean injectDrag(float fromX, float fromY, float toX, float toY, int duration);
    // 多指手势：每项为 JSON 字符串 {"x":..,"y":..,"action":"down/up/move"}
    boolean injectMultiTouch(in List<String> points);
    String execCommand(String command);
}
