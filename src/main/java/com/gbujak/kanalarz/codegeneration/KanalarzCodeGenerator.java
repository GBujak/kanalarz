package com.gbujak.kanalarz.codegeneration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class KanalarzCodeGenerator {

    public static void main(String[] args) {
        generateCode();
    }

    public static void generateCode() {
        StringBuilder generatedCode = new StringBuilder();

        generatedCode.append("package com.gbujak.kanalarz.generatedcode;\n\n");

        generatedCode.append("public class GeneratedClass {\n");
        generatedCode.append("\tpublic void generatedMethod() {\n");
        generatedCode.append("\t\tSystem.out.println(\"Generated code\");\n");
        generatedCode.append("\t}\n");
        generatedCode.append("}");

        try {
            var generatedDir = Paths.get("build/generated/source/java/com/gbujak/kanalarz/generatedcode").normalize();
            Files.createDirectories(generatedDir);
            var generatedFile = Paths.get(generatedDir.toString(), "GeneratedClass.java");
            Files.writeString(generatedFile, generatedCode.toString(), StandardOpenOption.CREATE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
