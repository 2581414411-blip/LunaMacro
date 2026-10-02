// IUserService.aidl
package com.nexus.agent.userservice;

interface IUserService {
    void injectTap(float x, float y);
    void injectSwipe(float x1, float y1, float x2, float y2, int duration);
    String execCommand(String command);
}
