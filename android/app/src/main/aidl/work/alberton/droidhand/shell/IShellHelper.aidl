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
    /** Launch [packageName] on [displayId] without moving that display to the front (keeps the user's focus). */
    String launchApp(int displayId, String packageName, boolean move);
    /**
     * Move the task of [packageName] that is on [fromDisplay] (the top one if empty)
     * to [toDisplay], by task id: never another copy of the same app. Optional [bounds]
     * (left, top, right, bottom) then resize it into a pop-up window. Returns the task id.
     */
    int moveTask(int fromDisplay, String packageName, int toDisplay, in int[] bounds);
    /**
     * Experimental: put the task of [packageName] on [fromDisplay] in split screen on the
     * main display next to the user's current app (reparented into the split stages).
     */
    String splitWith(int fromDisplay, String packageName);
    /** Left mouse click: unlike a touch, it does not close the user's keyboard. */
    boolean mouseClick(int displayId, int x, int y);
    int pid();
    void exit();
}
