package android.os;

public class Message {
    public Object obj;
    public int what;
    Handler target;

    public static Message obtain() {
        return new Message();
    }

    public void sendToTarget() {
        if (target != null) {
            target.sendMessage(this);
        }
    }
}
