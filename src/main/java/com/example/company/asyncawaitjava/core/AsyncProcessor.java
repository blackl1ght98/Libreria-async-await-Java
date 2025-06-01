package com.example.company.asyncawaitjava.core;

import com.example.company.asyncawaitjava.task.Task;
import com.example.company.asyncawaitjava.config.AsyncAwaitConfig;
import com.example.company.asyncawaitjava.annotations.Async;
import com.example.company.asyncawaitjava.annotations.Await;
import com.squareup.javapoet.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@SupportedAnnotationTypes({"com.example.company.asyncawaitjava.annotations.Async", "com.example.company.asyncawaitjava.annotations.Await"})
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
        if (roundEnv.processingOver()) {
            return false;
        }

        Map<TypeElement, List<ExecutableElement>> asyncMethods = new HashMap<>();
        Map<TypeElement, List<ExecutableElement>> awaitMethods = new HashMap<>();

        // Procesar métodos con @Async
        for (Element element : roundEnv.getElementsAnnotatedWith(Async.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                messager.printMessage(Diagnostic.Kind.WARNING, "Ignoring non-method element annotated with @Async", element);
                continue;
            }
            ExecutableElement method = (ExecutableElement) element;
            if (method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.FINAL)) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@Async not supported on static or final methods", method);
                continue;
            }
            TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();
            asyncMethods.computeIfAbsent(enclosingClass, k -> new ArrayList<>()).add(method);
        }

        // Procesar métodos con @Await
        for (Element element : roundEnv.getElementsAnnotatedWith(Await.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                messager.printMessage(Diagnostic.Kind.WARNING, "Ignoring non-method element annotated with @Await", element);
                continue;
            }
            ExecutableElement method = (ExecutableElement) element;
            TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();
            awaitMethods.computeIfAbsent(enclosingClass, k -> new ArrayList<>()).add(method);
        }

        // Generar clases wrapper
        for (TypeElement clazz : asyncMethods.keySet()) {
            try {
                generateAsyncWrapper(clazz, asyncMethods.getOrDefault(clazz, List.of()), awaitMethods.getOrDefault(clazz, List.of()));
                messager.printMessage(Diagnostic.Kind.NOTE, "Generated wrapper for class " + clazz.getSimpleName());
            } catch (IOException e) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Error generating wrapper for " + clazz.getSimpleName() + ": " + e.getMessage(), clazz);
                e.printStackTrace();
            }
        }

        return true;
    }

    private void generateAsyncWrapper(TypeElement enclosingClass, List<ExecutableElement> asyncMethods, List<ExecutableElement> awaitMethods) throws IOException {
        String packageName = elementUtils.getPackageOf(enclosingClass).getQualifiedName().toString();
        String className = enclosingClass.getSimpleName().toString();
        String asyncClassName = "Async" + className;

        TypeSpec.Builder wrapperClassBuilder = TypeSpec.classBuilder(asyncClassName)
                .addModifiers(Modifier.PUBLIC)
                .superclass(TypeName.get(enclosingClass.asType()));

        // Constructor
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(String.class, "name")
                .addStatement("super(name)")
                .build();
        wrapperClassBuilder.addMethod(constructor);

        // Generar métodos asíncronos para @Async
        for (ExecutableElement method : asyncMethods) {
            Async asyncAnnotation = method.getAnnotation(Async.class);
            String methodName = method.getSimpleName().toString();
            String asyncMethodName = methodName + asyncAnnotation.methodSuffix();
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
                asyncCodeBlock.add("return $T.runAsync(() -> {\n", CompletableFuture.class);
            } else {
                asyncCodeBlock.add("return $T.supplyAsync(() -> {\n", CompletableFuture.class);
            }
            asyncCodeBlock.beginControlFlow("try");
            if (returnType.equals(TypeName.VOID)) {
                asyncCodeBlock.addStatement("super.$L($L)", methodName, paramNames);
            } else {
                asyncCodeBlock.addStatement("return super.$L($L)", methodName, paramNames);
            }
            asyncCodeBlock.nextControlFlow("catch ($T e)", Exception.class);
            asyncCodeBlock.addStatement("throw new $T(e)", RuntimeException.class);
            asyncCodeBlock.endControlFlow();
            asyncCodeBlock.add("}, $T.getDefaultExecutor());\n", AsyncAwaitConfig.class);

            MethodSpec.Builder asyncMethod = MethodSpec.methodBuilder(asyncMethodName)
                    .addModifiers(method.getModifiers().stream()
                            .filter(m -> m != Modifier.SYNCHRONIZED && m != Modifier.ABSTRACT)
                            .collect(Collectors.toSet()))
                    .returns(asyncReturnType)
                    .addParameters(parameters)
                    .addJavadoc("Versión asíncrona del método $L. Devuelve un $T.\n", methodName, asyncReturnType)
                    .addCode(asyncCodeBlock.build());

            // Soporte para genéricos
            method.getTypeParameters().forEach(typeParam -> asyncMethod.addTypeVariable(TypeVariableName.get(typeParam)));

            // Soporte para excepciones declaradas
            method.getThrownTypes().forEach(thrownType -> asyncMethod.addException(TypeName.get(thrownType)));

            wrapperClassBuilder.addMethod(asyncMethod.build());
        }

        // Generar métodos síncronos para @Await
        for (ExecutableElement method : awaitMethods) {
            String methodName = method.getSimpleName().toString();
            TypeName returnType = TypeName.get(method.getReturnType());
            TypeName taskReturnType = returnType instanceof ParameterizedTypeName
                    ? ((ParameterizedTypeName) returnType).typeArguments.get(0)
                    : returnType;

            List<ParameterSpec> parameters = method.getParameters().stream()
                    .map(param -> ParameterSpec.builder(TypeName.get(param.asType()), param.getSimpleName().toString()).build())
                    .collect(Collectors.toList());
            String paramNames = parameters.stream()
                    .map(param -> param.name)
                    .collect(Collectors.joining(", "));

            CodeBlock.Builder syncCodeBlock = CodeBlock.builder();
            syncCodeBlock.addStatement("return $T.await(new $T<>($T.supplyAsync(() -> super.$L($L), $T.getDefaultExecutor()), $T.getDefaultExecutor()))",
                    AsyncAwaitJava.class, Task.class, CompletableFuture.class, methodName, paramNames, AsyncAwaitConfig.class, AsyncAwaitConfig.class);

            MethodSpec.Builder syncMethod = MethodSpec.methodBuilder(methodName + "Sync")
                    .addModifiers(method.getModifiers().stream()
                            .filter(m -> m != Modifier.SYNCHRONIZED && m != Modifier.ABSTRACT)
                            .collect(Collectors.toSet()))
                    .returns(taskReturnType)
                    .addParameters(parameters)
                    .addJavadoc("Versión síncrona del método $L que espera el resultado de un $T.\n", methodName, CompletableFuture.class)
                    .addCode(syncCodeBlock.build());

            // Soporte para genéricos
            method.getTypeParameters().forEach(typeParam -> syncMethod.addTypeVariable(TypeVariableName.get(typeParam)));

            // Soporte para excepciones declaradas
            method.getThrownTypes().forEach(thrownType -> syncMethod.addException(TypeName.get(thrownType)));
            syncMethod.addException(Exception.class); // Por el uso de await

            wrapperClassBuilder.addMethod(syncMethod.build());
        }

        JavaFile javaFile = JavaFile.builder(packageName, wrapperClassBuilder.build())
                .skipJavaLangImports(true)
                .build();
        javaFile.writeTo(filer);
        messager.printMessage(Diagnostic.Kind.NOTE, "Wrote wrapper: " + packageName + "." + asyncClassName);
    }
}