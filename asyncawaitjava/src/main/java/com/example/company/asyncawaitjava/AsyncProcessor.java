package com.example.company.asyncawaitjava;

import com.squareup.javapoet.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.example.company.asyncawaitjava.Async")
@SupportedSourceVersion(SourceVersion.RELEASE_24)
public class AsyncProcessor extends AbstractProcessor {
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elementUtils = processingEnv.getElementUtils();
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        System.out.println("Processing @Async annotations...");
        if (roundEnv.processingOver()) {
            return false;
        }

        Map<TypeElement, List<ExecutableElement>> classMethods = new HashMap<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Async.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                messager.printMessage(
                    javax.tools.Diagnostic.Kind.WARNING,
                    "Ignoring non-method element annotated with @Async",
                    element
                );
                continue;
            }
            ExecutableElement method = (ExecutableElement) element;
            TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();
            classMethods.computeIfAbsent(enclosingClass, k -> new ArrayList<>()).add(method);
        }

        for (Map.Entry<TypeElement, List<ExecutableElement>> entry : classMethods.entrySet()) {
            try {
                generateAsyncWrapper(entry.getKey(), entry.getValue());
                messager.printMessage(
                    javax.tools.Diagnostic.Kind.NOTE,
                    "Generated async wrapper for class " + entry.getKey().getSimpleName()
                );
            } catch (IOException e) {
                messager.printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "Error generating async wrapper for " + entry.getKey().getSimpleName() + ": " + e.getMessage(),
                    entry.getKey()
                );
                e.printStackTrace();
            }
        }
        return true;
    }

    private void generateAsyncWrapper(TypeElement enclosingClass, List<ExecutableElement> methods) throws IOException {
        String packageName = elementUtils.getPackageOf(enclosingClass).getQualifiedName().toString();
        String className = enclosingClass.getSimpleName().toString();
        String asyncClassName = "Async" + className;

        TypeSpec.Builder wrapperClassBuilder = TypeSpec.classBuilder(asyncClassName)
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeName.get(enclosingClass.asType()));

        MethodSpec constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(String.class, "name")
            .addStatement("super(name)")
            .build();
        wrapperClassBuilder.addMethod(constructor);

        for (ExecutableElement method : methods) {
            String methodName = method.getSimpleName().toString();
            TypeName returnType = TypeName.get(method.getReturnType());
            TypeName asyncReturnType = returnType.equals(TypeName.VOID)
                ? ParameterizedTypeName.get(ClassName.get(CompletableFuture.class), ClassName.get(Void.class))
                : ParameterizedTypeName.get(ClassName.get(CompletableFuture.class), returnType);

            List<ParameterSpec> parameters = method.getParameters().stream()
                .map(param -> ParameterSpec.builder(TypeName.get(param.asType()), param.getSimpleName().toString()).build())
                .collect(Collectors.toList());
            String paramNames = parameters.stream()
                .map(param -> param.name)
                .collect(Collectors.joining(", "));

            CodeBlock.Builder asyncCodeBlock = CodeBlock.builder();
            if (returnType.equals(TypeName.VOID)) {
                asyncCodeBlock.addStatement("return $T.runAsync(() -> {", CompletableFuture.class);
            } else {
                asyncCodeBlock.addStatement("return $T.supplyAsync(() -> {", CompletableFuture.class);
            }
            asyncCodeBlock.beginControlFlow("try");
            if (returnType.equals(TypeName.VOID)) {
                asyncCodeBlock.addStatement("super.$L($L)", methodName, paramNames);
            } else {
                asyncCodeBlock.addStatement("return super.$L($L)", methodName, paramNames);
            }
            asyncCodeBlock.nextControlFlow("catch ($T e)", InterruptedException.class);
            asyncCodeBlock.addStatement("throw new $T(e)", RuntimeException.class);
            asyncCodeBlock.endControlFlow();
            asyncCodeBlock.addStatement("}, $T.newVirtualThreadPerTaskExecutor())", java.util.concurrent.Executors.class);

            MethodSpec asyncMethod = MethodSpec.methodBuilder(methodName + "Async")
                .addModifiers(Modifier.PUBLIC)
                .returns(asyncReturnType)
                .addParameters(parameters)
                .addCode(asyncCodeBlock.build())
                .build();
            wrapperClassBuilder.addMethod(asyncMethod);
        }

        JavaFile javaFile = JavaFile.builder(packageName, wrapperClassBuilder.build())
            .build();
        javaFile.writeTo(filer);
        messager.printMessage(
            javax.tools.Diagnostic.Kind.NOTE,
            "Wrote async wrapper: " + packageName + "." + asyncClassName
        );
    }
}