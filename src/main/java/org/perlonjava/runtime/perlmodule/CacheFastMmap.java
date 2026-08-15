package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache::FastMmap's XS primitive API implemented with a JVM shared map.
 *
 * <p>The Perl layer retains expiry, serialization, callbacks, and atomic-operation
 * semantics. This backend replaces only the native mmap page store, which is not
 * loadable on the JVM.</p>
 */
public class CacheFastMmap extends PerlModuleBase {
    private static final Map<String, ConcurrentHashMap<String, Entry>> SHARED = new ConcurrentHashMap<>();
    private static volatile long timeOverride;

    private static final class Entry {
        RuntimeScalar value;
        long expireOn;
        int flags;
        Long modseq;
        long lastAccess;
    }

    private static final class State {
        final Map<String, RuntimeScalar> params = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
        boolean locked;
        long reads;
        long hits;
    }

    public CacheFastMmap() { super("Cache::FastMmap", false); }

    public static void initialize() {
        CacheFastMmap module = new CacheFastMmap();
        try {
            for (String name : new String[]{
                    "fc_new", "fc_set_param", "fc_init", "fc_hash", "fc_lock", "fc_unlock",
                    "fc_is_locked", "fc_read", "fc_write", "fc_delete", "fc_tombstone",
                    "fc_get_keys", "fc_get_page_details", "fc_reset_page_details", "fc_expunge",
                    "fc_set_time_override", "fc_close"}) {
                module.registerMethod(name, null);
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuntimeList fc_new(RuntimeArray args, int ctx) {
        return new RuntimeScalar(new State()).getList();
    }

    public static RuntimeList fc_set_param(RuntimeArray args, int ctx) {
        state(args.get(0)).params.put(args.get(1).toString(), new RuntimeScalar(args.get(2)));
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList fc_init(RuntimeArray args, int ctx) {
        State state = state(args.get(0));
        RuntimeScalar share = state.params.get("share_file");
        if (share != null && share.defined().getBoolean()) {
            String file = share.toString();
            boolean init = state.params.getOrDefault("init_file", new RuntimeScalar(0)).getBoolean();
            state.entries = SHARED.computeIfAbsent(file, ignored -> new ConcurrentHashMap<>());
            if (init) state.entries.clear();
            try {
                Path path = Path.of(file);
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.write(path, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to initialize cache share file " + file, e);
            }
        }
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList fc_hash(RuntimeArray args, int ctx) {
        String key = args.get(1).toString();
        int hash = key.hashCode() & 0x7fffffff;
        int pages = state(args.get(0)).params.getOrDefault("num_pages", new RuntimeScalar(89)).getInt();
        RuntimeList out = new RuntimeList();
        out.add(new RuntimeScalar(hash % Math.max(1, pages)));
        out.add(new RuntimeScalar(hash));
        return out;
    }

    public static RuntimeList fc_lock(RuntimeArray args, int ctx) {
        state(args.get(0)).locked = true;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList fc_unlock(RuntimeArray args, int ctx) {
        state(args.get(0)).locked = false;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList fc_is_locked(RuntimeArray args, int ctx) {
        return new RuntimeScalar(state(args.get(0)).locked).getList();
    }

    public static RuntimeList fc_read(RuntimeArray args, int ctx) {
        State state = state(args.get(0));
        state.reads++;
        Entry entry = state.entries.get(args.get(2).toString());
        if (entry != null && expired(entry)) {
            state.entries.remove(args.get(2).toString(), entry);
            entry = null;
        }
        RuntimeList out = new RuntimeList();
        if (entry == null) {
            out.add(new RuntimeScalar());
            out.add(new RuntimeScalar(0));
            out.add(new RuntimeScalar(0));
            out.add(new RuntimeScalar());
            out.add(new RuntimeScalar());
        } else {
            state.hits++;
            entry.lastAccess = now();
            out.add(new RuntimeScalar(entry.value));
            out.add(new RuntimeScalar(entry.flags));
            out.add(new RuntimeScalar(1));
            out.add(entry.expireOn < 0 ? new RuntimeScalar() : new RuntimeScalar(entry.expireOn));
            out.add(entry.modseq == null ? new RuntimeScalar() : new RuntimeScalar(entry.modseq));
        }
        return out;
    }

    public static RuntimeList fc_write(RuntimeArray args, int ctx) {
        State state = state(args.get(0));
        String key = args.get(2).toString();
        long expireOn = args.get(4).getLong();
        int flags = args.get(5).getInt();
        Long modseq = args.size() > 6 && args.get(6).defined().getBoolean()
                ? args.get(6).getLong() : null;
        Entry old = state.entries.get(key);
        if (old != null && old.modseq != null && (modseq == null || modseq < old.modseq)) {
            return new RuntimeScalar(-1).getList();
        }
        Entry entry = new Entry();
        entry.value = new RuntimeScalar(args.get(3));
        entry.expireOn = expireOn;
        entry.flags = flags;
        entry.modseq = modseq;
        entry.lastAccess = now();
        state.entries.put(key, entry);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList fc_delete(RuntimeArray args, int ctx) {
        Entry removed = state(args.get(0)).entries.remove(args.get(2).toString());
        RuntimeList out = new RuntimeList();
        out.add(new RuntimeScalar(removed != null));
        out.add(new RuntimeScalar(removed == null ? 0 : removed.flags));
        return out;
    }

    public static RuntimeList fc_tombstone(RuntimeArray args, int ctx) {
        State state = state(args.get(0));
        String key = args.get(2).toString();
        long modseq = args.get(4).getLong();
        Entry old = state.entries.get(key);
        if (old != null && old.modseq != null && old.modseq > modseq) return new RuntimeScalar(0).getList();
        Entry tombstone = new Entry();
        tombstone.value = new RuntimeScalar();
        tombstone.expireOn = args.get(3).getLong();
        tombstone.modseq = modseq;
        tombstone.lastAccess = now();
        state.entries.put(key, tombstone);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList fc_get_keys(RuntimeArray args, int ctx) {
        State state = state(args.get(0));
        int mode = args.size() > 1 ? args.get(1).getInt() : 0;
        RuntimeList out = new RuntimeList();
        state.entries.forEach((key, entry) -> {
            if (expired(entry)) return;
            if (mode == 0) {
                out.add(new RuntimeScalar(key));
            } else {
                RuntimeHash detail = new RuntimeHash();
                detail.put("key", new RuntimeScalar(key));
                detail.put("last_access", new RuntimeScalar(entry.lastAccess));
                detail.put("expire_on", entry.expireOn < 0 ? new RuntimeScalar() : new RuntimeScalar(entry.expireOn));
                detail.put("flags", new RuntimeScalar(entry.flags));
                if (mode >= 2) detail.put("value", new RuntimeScalar(entry.value));
                out.add(detail.createAnonymousReference());
            }
        });
        return out;
    }

    public static RuntimeList fc_get_page_details(RuntimeArray args, int ctx) {
        State state = state(args.get(0));
        RuntimeList out = new RuntimeList();
        out.add(new RuntimeScalar(state.reads));
        out.add(new RuntimeScalar(state.hits));
        return out;
    }

    public static RuntimeList fc_reset_page_details(RuntimeArray args, int ctx) {
        State state = state(args.get(0));
        state.reads = state.hits = 0;
        return new RuntimeList();
    }

    public static RuntimeList fc_expunge(RuntimeArray args, int ctx) {
        State state = state(args.get(0));
        int mode = args.get(1).getInt();
        RuntimeList removed = new RuntimeList();
        state.entries.entrySet().removeIf(item -> {
            Entry entry = item.getValue();
            boolean remove = mode == 1 || expired(entry);
            if (remove && args.get(2).getBoolean()) {
                RuntimeHash detail = new RuntimeHash();
                detail.put("key", new RuntimeScalar(item.getKey()));
                detail.put("value", new RuntimeScalar(entry.value));
                detail.put("expire_on", new RuntimeScalar(entry.expireOn));
                detail.put("flags", new RuntimeScalar(entry.flags));
                removed.add(detail.createAnonymousReference());
            }
            return remove;
        });
        return removed;
    }

    public static RuntimeList fc_set_time_override(RuntimeArray args, int ctx) {
        timeOverride = args.isEmpty() ? 0 : args.get(0).getLong();
        return new RuntimeList();
    }

    public static RuntimeList fc_close(RuntimeArray args, int ctx) { return new RuntimeList(); }

    private static State state(RuntimeScalar scalar) {
        if (scalar.type == RuntimeScalarType.JAVAOBJECT && scalar.value instanceof State state) return state;
        throw new IllegalArgumentException("Invalid Cache::FastMmap native cache handle");
    }

    private static long now() { return timeOverride != 0 ? timeOverride : System.currentTimeMillis() / 1000L; }
    private static boolean expired(Entry entry) { return entry.expireOn > 0 && entry.expireOn <= now(); }
}
