### Description 

A simple plugin for generating an xml schema from the compiled classes.

### Usage

```xml
<plugin>
    <groupId>com.idvp.plugins</groupId>
    <artifactId>postprocess-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <phase>process-classes</phase>
            <goals>
                <goal>aop</goal>
            </goals>
        </execution>
    </executions>
   
</plugin>
```