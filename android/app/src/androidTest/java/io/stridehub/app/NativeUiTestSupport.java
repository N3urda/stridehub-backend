package io.stridehub.app;

import android.view.View;
import android.view.ViewGroup;

final class NativeUiTestSupport {
    static boolean containsWebSurface(View view) {
        if(view.getClass().getName().startsWith("android.webkit."))return true;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)if(containsWebSurface(group.getChildAt(i)))return true;
        }
        return false;
    }
    private NativeUiTestSupport() { }
}
