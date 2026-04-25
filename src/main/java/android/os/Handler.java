package android.os;

public class Handler {

    public interface Callback {
        boolean handleMessage(Message msg);
    }

    private final Callback callback;

    public Handler() {
        this.callback = null;
    }

    public Handler(Looper looper) {
        this.callback = null;
    }

    public Handler(Looper looper, Callback callback) {
        this.callback = callback;
    }

    public boolean post(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
        return true;
    }

    public boolean postDelayed(Runnable runnable, long delayMillis) {
        if (runnable != null) {
            runnable.run();
        }
        return true;
    }

    public Message obtainMessage() {
        Message message = Message.obtain();
        message.target = this;
        return message;
    }

    public Message obtainMessage(int what) {
        Message message = obtainMessage();
        message.what = what;
        return message;
    }

    public boolean sendMessage(Message message) {
        if (message == null) {
            return false;
        }
        message.target = this;
        dispatchMessage(message);
        return true;
    }

    public void dispatchMessage(Message message) {
        if (callback != null && callback.handleMessage(message)) {
            return;
        }
        handleMessage(message);
    }

    public void handleMessage(Message message) {
    }
}
