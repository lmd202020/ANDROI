package com.minhduc.elite;

interface IPerformanceService {
    String[] readAnimationValues() = 1;

    String applyAnimation(
        boolean enable,
        String windowScale,
        String transitionScale,
        String animatorScale
    ) = 2;

    String installGameAssets(String sourcePath, String packageName) = 3;

    String restoreGameAssets(String packageName) = 4;

    void destroy() = 16777114;
}
