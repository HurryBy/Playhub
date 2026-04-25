package android.content;

public class ComponentName {

    private final String packageName;
    private final String className;

    public ComponentName(String packageName, String className) {
        this.packageName = packageName;
        this.className = className;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getClassName() {
        return className;
    }
}
