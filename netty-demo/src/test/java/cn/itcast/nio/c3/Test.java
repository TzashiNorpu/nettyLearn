package cn.itcast.nio.c3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Test {
    public static void main(String[] args) throws IOException {
        String targetName = "hello-copy.txt";
        Path path = Paths.get("hello.txt");
        if (Files.isRegularFile(path)) {
            Files.copy(path, Paths.get(targetName));
        }
    }
}
