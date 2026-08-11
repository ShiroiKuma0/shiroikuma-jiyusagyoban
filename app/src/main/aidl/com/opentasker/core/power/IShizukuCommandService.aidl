package com.opentasker.core.power;

interface IShizukuCommandService {
    String execute(String actionId, in String[] argv, out int[] exitCode);
    int captureScreenshot(String actionId, String path);
    void destroy();
}
