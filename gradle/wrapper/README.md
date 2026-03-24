# Gradle Wrapper Jar

`gradle-wrapper.jar` is intentionally not committed in this environment because binary files are not supported by the PR system.

To regenerate it locally, run:

```bash
gradle wrapper --gradle-version 8.14.3
```

This will recreate `gradle/wrapper/gradle-wrapper.jar` for local builds.
