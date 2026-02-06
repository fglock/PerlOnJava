# GraalVM Native Image Support Investigation

## What Was Tried

### 1. Maven Configuration
Added GraalVM support to pom.xml in a dedicated profile:

```xml
<profiles>
    <profile>
        <id>native</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.graalvm.buildtools</groupId>
                    <artifactId>native-maven-plugin</artifactId>
                    <version>${native.maven.plugin.version}</version>
                    <extensions>true</extensions>
                    <executions>
                        <execution>
                            <id>build-native</id>
                            <goals>
                                <goal>build</goal>
                            </goals>
                            <phase>package</phase>
                        </execution>
                    </executions>
                    <configuration>
                        <imageName>${imageName}</imageName>
                        <buildArgs>
                            <buildArg>--no-fallback</buildArg>
                        </buildArgs>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

### 2. Runtime Method Detection
Used GraalVM tracing agent to capture required runtime methods:

```bash
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image -jar target/perlonjava-3.0.0.jar examples/life.pl
```

## Results

The native image build is not currently viable due to fundamental GraalVM limitations:

1. Many Perl runtime methods are optimized out during native image generation
2. Runtime class loading is required but not supported by GraalVM native image:
   ```
   -H:+SupportRuntimeClassLoading is not yet supported.
   ```

## Current Status

PerlOnJava requires JVM dynamic features for proper operation. Continue using standard JVM builds:

```bash
mvn clean package
```
