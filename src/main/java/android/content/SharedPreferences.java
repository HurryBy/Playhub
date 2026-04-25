package android.content;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public interface SharedPreferences {

    Map<String, ?> getAll();

    String getString(String key, String defValue);

    Set<String> getStringSet(String key, Set<String> defValues);

    int getInt(String key, int defValue);

    long getLong(String key, long defValue);

    float getFloat(String key, float defValue);

    boolean getBoolean(String key, boolean defValue);

    boolean contains(String key);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor putStringSet(String key, Set<String> values);

        Editor putInt(String key, int value);

        Editor putLong(String key, long value);

        Editor putFloat(String key, float value);

        Editor putBoolean(String key, boolean value);

        Editor remove(String key);

        Editor clear();

        boolean commit();

        void apply();
    }

    class InMemorySharedPreferences implements SharedPreferences {

        private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(data);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = data.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = data.get(key);
            return value instanceof Set ? (Set<String>) value : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = data.get(key);
            return value instanceof Number ? ((Number) value).intValue() : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = data.get(key);
            return value instanceof Number ? ((Number) value).longValue() : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = data.get(key);
            return value instanceof Number ? ((Number) value).floatValue() : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = data.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return data.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new EditorImpl(data);
        }

        static class EditorImpl implements Editor {

            private final ConcurrentHashMap<String, Object> target;
            private final Map<String, Object> pending = new ConcurrentHashMap<>();
            private final Set<String> removed = ConcurrentHashMap.newKeySet();
            private volatile boolean clear = false;

            EditorImpl(ConcurrentHashMap<String, Object> target) {
                this.target = target;
            }

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value);
                removed.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                pending.put(key, values);
                removed.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pending.put(key, value);
                removed.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                pending.put(key, value);
                removed.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pending.put(key, value);
                removed.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pending.put(key, value);
                removed.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removed.add(key);
                pending.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                pending.clear();
                removed.clear();
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                synchronized (target) {
                    if (clear) {
                        target.clear();
                    }
                    for (String key : removed) {
                        target.remove(key);
                    }
                    target.putAll(pending);
                }
            }
        }
    }
}
