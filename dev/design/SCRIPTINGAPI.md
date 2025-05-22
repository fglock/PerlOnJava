## Using Java Scripting API

### Using jrunscript

- jrunscript implements a generic interactive shell using Java Scripting API.

- Note that `jrunscript` creates a new scope every time, so it doesn't keep lexical variables from one line to the next.

  ```sh
  $ jrunscript -cp target/perlonjava-3.0.0.jar -l perl 
  Perl5> my $sub = sub { say $_[0] }; $sub->($_) for 4,5,6;
  4
  5
  6
  []
  Perl5>
  ```

### PerlScriptEngine installation

To use `PerlScriptEngine`, include the necessary dependencies in your project. For example, if you are using Maven, add
the following to your `pom.xml`:

```xml

<dependency>
    <groupId>org.perlonjava</groupId>
    <artifactId>perl-script-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

### PerlScriptEngine usage

Here is an example of how to use `PerlScriptEngine` to execute a simple Perl script:

```java
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

public class Main {
    public static void main(String[] args) {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("perl");

        String script = "print 'Hello, Perl from Java!';";

        try {
            engine.eval(script);
        } catch (ScriptException e) {
            e.printStackTrace();
        }
    }
}
```

