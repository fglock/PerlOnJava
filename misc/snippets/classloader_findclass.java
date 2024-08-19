import java.net.URLClassLoader;

public class ParentClassLoader extends URLClassLoader {
    public ParentClassLoader(URL[] urls) {
        super(urls, null);
    }
}

public class ChildClassLoader extends URLClassLoader {
    private final ParentClassLoader parent;

    public ChildClassLoader(URL[] urls, ParentClassLoader parent) {
        super(urls, null);
        this.parent = parent;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException e) {
            // Delegate to parent if not found
            return parent.loadClass(name);
        }
    }
}

