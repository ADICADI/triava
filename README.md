# triava

The triava project contains several of trivago's core libraries for Java-based projects: caching, collections, annotations, concurrency libraries and more.

## Building:
triava has no dependencies apart from Java 7. The following will build everything, including Javadoc and a source jar:

`mvn package`

This will create three artefacts in the target/ folder:

- triava-[version].jar
- triava-[version]-sources.jar
- triava-[version]-javadoc.jar

## Usage:
To start, look at the [examples folder](./src/examples/java/com/trivago/examples).

### Usage in Maven: pom.xml
```
  <dependencies>
    <dependency>
      <groupId>com.trivago</groupId>
      <artifactId>triava</artifactId>
      <version>0.9.0</version>
    </dependency>
  </dependencies>
```

triava in [Maven Central](http://search.maven.org/#search|ga|1|a%3A%22triava%22)

### Usage in Gradle: build.gradle
```
dependencies {
	compile 'com.trivago:triava:0.9.0'
}
```

## License
Licensed under the Apache License, Version 2.0

## Examples
Have a look at the [examples folder](./src/examples/java/com/trivago/examples).
