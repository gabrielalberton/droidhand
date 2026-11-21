package work.alberton.droidhand.shell;

import android.view.MotionEvent;
import android.view.Surface;

/** Runs as the shell user (started over ADB); owns trusted background displays. */
interface IShellHelper {
    int createDisplay(String name, int width, int height, int dpi, in Surface surface);
    void setSurface(int displayId, in Surface surface);
    void resizeDisplay(int displayId, int width, int height, int dpi);
    boolean injectMotion(int displayId, in MotionEvent event);
    boolean injectKey(int displayId, int keyCode);
    void releaseDisplay(int displayId);
    int pid();
    void exit();
}
