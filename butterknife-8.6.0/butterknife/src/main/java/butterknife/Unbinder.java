package butterknife;

import android.support.annotation.UiThread;

/** An unbinder contract that will unbind views when called. */
public interface Unbinder {
    Unbinder EMPTY = new Unbinder() {
        @Override
        public void unbind() { }
    };
    @UiThread
    void unbind();
}
