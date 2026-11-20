package android.view.accessibility;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Scriptable stand-in for android.view.accessibility.AccessibilityNodeInfo, used ONLY by
 * tools/selftest. It lets the real selector code walk a hand-built tree on a plain JVM.
 * Never packaged in the app.
 */
public class AccessibilityNodeInfo {
    public static final int ACTION_CLICK = 0x10;
    public static final int ACTION_ACCESSIBILITY_FOCUS = 0x40;
    public static final int ACTION_SCROLL_FORWARD = 0x1000;
    public static final int ACTION_SCROLL_BACKWARD = 0x2000;

    /** Receives performAction calls so a model can react like the real widget would. */
    public interface ActionHandler {
        boolean onAction(AccessibilityNodeInfo node, int action);
    }

    public CharSequence text;
    public CharSequence contentDescription;
    public CharSequence className = "android.view.View";
    public CharSequence packageName = "com.example.target";
    public String viewId;
    public final Rect bounds = new Rect();
    public boolean visibleToUser = true;
    public boolean enabled = true;
    public boolean clickable;
    public boolean selected;
    public boolean checked;
    public boolean checkable;
    public boolean focusable;
    public boolean scrollable;
    public ActionHandler handler;
    public AccessibilityNodeInfo parent;
    public final List<AccessibilityNodeInfo> children = new ArrayList<>();
    /** Every action performed on this node, in order (for assertions). */
    public final List<Integer> performed = new ArrayList<>();

    public AccessibilityNodeInfo add(AccessibilityNodeInfo child) {
        child.parent = this;
        children.add(child);
        return this;
    }

    public CharSequence getText() {
        return text;
    }

    public CharSequence getContentDescription() {
        return contentDescription;
    }

    public CharSequence getClassName() {
        return className;
    }

    public CharSequence getPackageName() {
        return packageName;
    }

    public String getViewIdResourceName() {
        return viewId;
    }

    public int getChildCount() {
        return children.size();
    }

    public AccessibilityNodeInfo getChild(int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }

    public AccessibilityNodeInfo getParent() {
        return parent;
    }

    public void getBoundsInScreen(Rect out) {
        out.set(bounds);
    }

    public boolean isVisibleToUser() {
        return visibleToUser;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isClickable() {
        return clickable;
    }

    public boolean isSelected() {
        return selected;
    }

    public boolean isChecked() {
        return checked;
    }

    public boolean isCheckable() {
        return checkable;
    }

    public boolean isFocusable() {
        return focusable;
    }

    public boolean isScrollable() {
        return scrollable;
    }

    public boolean performAction(int action) {
        performed.add(action);
        return handler != null && handler.onAction(this, action);
    }

    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String id) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(this, id, out);
        return out;
    }

    private static void collect(AccessibilityNodeInfo node, String id, List<AccessibilityNodeInfo> out) {
        if (id != null && id.equals(node.viewId)) out.add(node);
        for (AccessibilityNodeInfo child : node.children) collect(child, id, out);
    }
}
