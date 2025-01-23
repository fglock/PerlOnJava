```java
public class DirectoryChanger {
    private Path currentDirectory;
    
    public DirectoryChanger() {
        currentDirectory = Paths.get(System.getProperty("user.dir"));
    }
    
    public boolean chdir(String newDir) {
        try {
            Path newPath = Paths.get(newDir).toAbsolutePath();
            if (Files.exists(newPath) && Files.isDirectory(newPath)) {
                currentDirectory = newPath;
                System.setProperty("user.dir", newPath.toString());
                return true;
            }
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }
    
    public Path getCurrentDirectory() {
        return currentDirectory;
    }
    
    // Use this for operations relative to current directory
    public Path resolveFile(String filename) {
        return currentDirectory.resolve(filename);
    }
}
```

Usage:

```java
DirectoryChanger dc = new DirectoryChanger();
if (dc.chdir("/some/path")) {
    // Use dc.resolveFile() for relative paths
    Path file = dc.resolveFile("myfile.txt");
    // Work with file...
}
```
