automate the versioning process to transition from a SNAPSHOT version to a release version

# Gradle

Gradle Release Plugin: Use the gradle-release plugin to automate the release process. 
This plugin can help manage version increments and tagging in the version control system.

Add the plugin to build.gradle:

```
plugins {
    id 'net.researchgate.release' version '2.8.1'
}
```

Configure the plugin to update the version:

```
release {
    failOnCommitNeeded = true
    failOnPublishNeeded = true
    failOnSnapshotDependencies = true
    preTagCommitMessage = '[Gradle Release Plugin] - pre tag commit: '
    tagCommitMessage = '[Gradle Release Plugin] - creating tag: '
    newVersionCommitMessage = '[Gradle Release Plugin] - new version commit: '
}
```

Run the release task:

```
./gradlew release
```

This will handle the version bump, create a Git tag, and update the version to the next snapshot.

# Maven

Maven Release Plugin: Maven has a built-in release plugin that can automate the release process, including versioning.

Add the plugin to pom.xml:

```
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-release-plugin</artifactId>
            <version>3.0.0-M5</version>
        </plugin>
    </plugins>
</build>
```

Use the release plugin to prepare and perform a release:

```
mvn release:prepare
mvn release:perform
```

This will update the pom.xml to a release version, create a tag in the version control system, 
and then update to the next snapshot version.

