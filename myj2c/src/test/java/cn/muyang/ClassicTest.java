package cn.muyang;

import cn.muyang.helpers.ProcessHelper;
import cn.muyang.helpers.ProcessHelper.ProcessResult;
import cn.muyang.xml.Config;
import org.junit.jupiter.api.function.Executable;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ClassicTest implements Executable {

    private final Path testData;
    private Path temp;
    private final String testName;

    ClassicTest(Path path, String testName) {
        testData = path;
        this.testName = testName;
    }

    private void clean() {
        try {
            Files.walk(temp)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    @SuppressWarnings("RedundantStringFormatCall")
    @Override
    public void execute() throws Throwable {
        try {
            System.err.println("Running test \"" + testName + "\"");
            System.out.println("Preparing...");

            temp = Files.createTempDirectory(String.format("native-obfuscator-test-%s-", testData.toFile().getName()));

            Path tempSource = temp.resolve("source");
            Path tempClasses = temp.resolve("classes");
            Files.createDirectories(tempSource);
            Files.createDirectories(tempClasses);

            Path idealJar = temp.resolve("test.jar");

            List<Path> javaFiles = new ArrayList<>();
            List<Path> resourceFiles = new ArrayList<>();
            Files.find(testData, 10, (path, attr) -> true)
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
            Files.find(testData, 10, (path, attr) -> attr.isDirectory() || !path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(resourceFiles::add);

            Optional<String> mainClassOptional = javaFiles.stream()
                    .filter(uncheckedPredicate(p -> Files.lines(p).collect(Collectors.joining("\n"))
                            .matches("(?s).*public(\\s+static)?\\s+void\\s+main.*")))
                    .map(p -> p.getFileName().toString())
                    .map(f -> f.substring(0, f.lastIndexOf('.')))
                    .findAny();

            if (!mainClassOptional.isPresent()) {
                System.out.println("Can't find main class");
                return;
            }

            javaFiles.forEach(unchecked(p -> Files.copy(p, tempSource.resolve(p.getFileName()))));
            resourceFiles.forEach(unchecked(p -> {
                Path target = temp.resolve(testData.relativize(p));
                Files.createDirectories(target.getParent());
                Files.copy(p, target);
            }));

            System.out.println("Compiling...");

            List<String> javacParameters = new ArrayList<>(Arrays.asList("javac", "-d", tempClasses.toString()));
            javaFiles.stream().map(Path::toString).forEach(javacParameters::add);

            ProcessHelper.run(temp, 10_000, javacParameters)
                    .check("Compilation");

            List<String> jarParameters = new ArrayList<>(Arrays.asList(
                    "jar", "cvfe", idealJar.toString(), mainClassOptional.get(),
                    "-C", tempClasses + File.separator, "."));
            resourceFiles.stream().map(Path::toString).forEach(jarParameters::add);
            ProcessHelper.run(temp, 10_000,
                            jarParameters)
                    .check("Jar command");

            System.out.println("Ideal...");

            ProcessResult idealRunResult = ProcessHelper.run(temp, 600 * 1000,
                    Arrays.asList("java", "-Dseed=1337", "-jar", idealJar.toString()));
            System.out.println(String.format("Took %dms", idealRunResult.execTime));
            //idealRunResult.check("Ideal run");

            for (Platform platform : Platform.values()) {
                System.out.println(String.format("Processing platform %s...", platform.toString()));

                Path tempOutput = temp.resolve(String.format("output_%s", platform));
                Path tempCpp = tempOutput.resolve("cpp");
                Files.createDirectories(tempOutput);
                Files.createDirectories(tempCpp);
                Path resultJar = tempOutput.resolve("test.jar");

                File file = new File(System.getProperty("user.dir") + "\\config-test.xml");
                //         java8的简写方式
                StringBuilder stringBuilder = new StringBuilder();
                try (BufferedReader br = Files.newBufferedReader(file.toPath())) {
                    String str;
                    while ((str = br.readLine()) != null) {
                        stringBuilder.append(str);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                MYObfuscator obfuscator = new MYObfuscator();
                Serializer serializer = new Persister();
                Config configInfo = serializer.read(Config.class, stringBuilder.toString());
                obfuscator.preProcess(idealJar, configInfo, false);
                obfuscator.process(idealJar, resultJar, tempOutput, configInfo, new ArrayList<Path>(), "x64-windows", null, false, false);

                System.out.println("Compiling CPP code...");

                ProcessResult compileRunresult = ProcessHelper.run(tempCpp, 3000 * 1000,
                        Arrays.asList("D:\\jnic\\zig-windows-x86_64-0.9.1\\zig.exe", "cc", "-O2", "-fno-sanitize=undefined", "-funroll-loops", "-target", "x86_64-windows-gnu", "-fPIC", "-shared", "-s", "-fvisibility=hidden", "-fvisibility-inlines-hidden", "-I.\\", "-o.\\build\\lib\\x64-windows.dll", "myj2c.c"));
                System.out.println(String.format("cmd %s %s", tempCpp, compileRunresult.commandLine));
                System.out.println(String.format("Took %dms", compileRunresult.execTime));
                compileRunresult.check("zig build");


                Files.find(tempCpp.resolve("build").resolve("lib"), 1, (path, args) -> Files.isRegularFile(path))
                        .forEach(unchecked(p -> Files.copy(p, tempOutput.resolve(p.getFileName()))));

                System.out.println("Running test...");

                long timeout = 1200 * 1000;
                ProcessResult testRunResult = ProcessHelper.run(tempOutput, timeout,
                        Arrays.asList("java",
                                "-Djava.library.path=.",
                                "-Dseed=1337",
                                "-Dtest.src=" + temp.toString(),
                                "-jar", resultJar.toString()));
                System.out.println(String.format("Took %dms", testRunResult.execTime));
                //testRunResult.check("Test run");

                if (!testRunResult.stdout.equals(idealRunResult.stdout)) {
                    // Some tests are random based
                    Pattern testResult = Pattern.compile("^Passed = \\d+,? failed = (\\d+)$", Pattern.MULTILINE);
                    Matcher matcher = testResult.matcher(testRunResult.stdout);
                    if (matcher.find()) {
                        if (!matcher.group(1).equals("0")) {
                            fail(testRunResult, idealRunResult);
                        }
                    } else {
                        fail(testRunResult, idealRunResult);
                    }
                }
                System.out.println("OK");
            }
            clean();
        } catch (IOException | RuntimeException e) {
            e.printStackTrace(System.err);
            throw e;
        }
    }

    private void fail(ProcessResult testRun, ProcessResult ideaRun) {
        System.err.println("Ideal:");
        System.err.println(ideaRun.stdout);
        System.err.println("Test:");
        System.err.println(testRun.stdout);
        throw new RuntimeException("Ideal != Test");
    }

    private <T> Predicate<T> uncheckedPredicate(UncheckedPredicate<T> consumer) {
        return value -> {
            try {
                return consumer.test(value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private <T> Consumer<T> unchecked(UncheckedConsumer<T> consumer) {
        return value -> {
            try {
                consumer.accept(value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private interface UncheckedConsumer<T> {
        void accept(T value) throws Exception;
    }

    private interface UncheckedPredicate<T> {
        boolean test(T value) throws Exception;
    }

}
