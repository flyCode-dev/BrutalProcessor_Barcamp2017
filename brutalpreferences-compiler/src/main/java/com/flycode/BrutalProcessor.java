package com.flycode;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public class BrutalProcessor extends AbstractProcessor {
    private Filer filer;
    private Messager messager;
    private Elements elementUtils;
    private Types typeUtils;
    private ArrayList<TypeMirror> supportedTypes;
    private ArrayList<String> preferencesMethods;
    private ArrayList<String> preferencesDefaults;

    /**
     * This methods will be called to initialize our BrutalProcessor.
     * @param processingEnv This is the environment where our processor runs.
     */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        // Extract some tools that we will need in future
        // Filer will help us saving generated source code
        filer = processingEnv.getFiler();
        // Messager will help to report issues back to developer
        messager = processingEnv.getMessager();
        // Element and Type utils will help with some actions with elements and types
        elementUtils = processingEnv.getElementUtils();
        typeUtils = processingEnv.getTypeUtils();

        // This is the list of types that Shared Preferences supports
        supportedTypes = new ArrayList<>();
        // int
        supportedTypes.add(typeUtils.getPrimitiveType(TypeKind.INT));
        // long
        supportedTypes.add(typeUtils.getPrimitiveType(TypeKind.LONG));
        // float
        supportedTypes.add(typeUtils.getPrimitiveType(TypeKind.FLOAT));
        // boolean
        supportedTypes.add(typeUtils.getPrimitiveType(TypeKind.BOOLEAN));
        // String
        supportedTypes.add(elementUtils.getTypeElement("java.lang.String").asType());
        // Set<String>
        supportedTypes.add(typeUtils.getDeclaredType(
                elementUtils.getTypeElement("java.util.Set"),
                elementUtils.getTypeElement("java.lang.String").asType()
        ));

        // We will use this list to generate setter and getter methods code
        preferencesMethods = new ArrayList<>();
        preferencesMethods.add("Int");
        preferencesMethods.add("Long");
        preferencesMethods.add("Float");
        preferencesMethods.add("Boolean");
        preferencesMethods.add("String");
        preferencesMethods.add("StringSet");

        // This is the list of default values needed for Shared Preferences getters
        preferencesDefaults = new ArrayList<>();
        preferencesDefaults.add("0");
        preferencesDefaults.add("0");
        preferencesDefaults.add("0");
        preferencesDefaults.add("false");
        preferencesDefaults.add("\"\"");
        preferencesDefaults.add("new HashSet<String>()");
    }

    /**
     * This method is called on each processing round
     * @param annotations The set of annotations, that our processor is asked to process
     * @param roundEnv The environment of of processed round. We are interested in annotated classes and if round is final
     * @return true if compiler needs to interrupt annotation processing, false otherwise
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Retrieve classes that are annotated with BrutalPreferences
        Set<? extends Element> elementList = roundEnv.getElementsAnnotatedWith(BrutalPreferences.class);

        for (Element element : elementList) {
            // Check that element is really a class
            if (element.getKind() != ElementKind.CLASS) {
                return true;
            }

            // Get preferences name from annotation
            BrutalPreferences brutalPreferences = element.getAnnotation(BrutalPreferences.class);
            String preferencesName = brutalPreferences.value();
            String className = element.getSimpleName() + "Preferences";

            // Start building preferences class
            TypeSpec.Builder typeSpec = TypeSpec
                    .classBuilder(className)
                    .addModifiers(Modifier.PUBLIC);

            // We will need this arrays to accumulate setters, getters and fields
            ArrayList<FieldSpec> fields= new ArrayList<>();
            ArrayList<MethodSpec> getters = new ArrayList<>();
            ArrayList<MethodSpec> setters = new ArrayList<>();

            // We will need this code blocks to accumulate constructor and appy method code
            CodeBlock.Builder applyCodeBlock = CodeBlock.builder();
            CodeBlock.Builder constructorCodeBlock = CodeBlock.builder();

            // Build the SharedPreferences field
            FieldSpec preferencesFieldSpec = FieldSpec
                    .builder(
                            ClassName.get("android.content", "SharedPreferences"),
                            "preferences",
                            Modifier.PRIVATE, Modifier.FINAL
                    )
                    .build();

            // Add it to the class
            typeSpec.addField(preferencesFieldSpec);

            // Loop through sub elements to find those we need to save with share preferences
            for (Element subElement : element.getEnclosedElements()) {
                Brutal brutal = subElement.getAnnotation(Brutal.class);

                // If sub element is not annotated with Brutal, we have no work to do with it
                if (brutal == null) {
                    continue;
                }

                // Annotated sub element must be a field
                if (subElement.getKind() != ElementKind.FIELD) {
                    return true;
                }

                // Annotated sub element must be of supported type
                if (!isSupported(subElement.asType())) {
                    return true;
                }

                // Extract the name of sub element
                String name = subElement.getSimpleName().toString();

                // Extract the method name for shared preferences getter and setter
                String method = getMethod(subElement.asType());

                // Extract the default value
                String defaultValue = getDefault(subElement.asType());

                // Extract the type name of sub element (int, string, etc.)
                TypeName typeName = TypeName.get(subElement.asType());

                // Build the field for this sub element
                FieldSpec fieldSpec = FieldSpec
                        .builder(
                                typeName,
                                name,
                                Modifier.PRIVATE
                        )
                        .build();

                // Build the getter method for sub element
                MethodSpec getterMethodSpec = MethodSpec
                        .methodBuilder(getGetterName(name))
                        .addModifiers(Modifier.PUBLIC)
                        .returns(typeName)
                        .addStatement("return $L", name)
                        .build();

                // Build the setter method for sub element
                MethodSpec setterMethodSpec = MethodSpec
                        .methodBuilder(getSetterName(name))
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(
                                typeName,
                                name
                        )
                        .addStatement("this.$L = $L", name, name)
                        .build();

                // Add the line that gets sub element from Shared Preferences
                constructorCodeBlock
                        .addStatement("$L = preferences.get$L($S, $L)", name, method, brutal.value(), defaultValue);

                // Add the line that puts sub element into Shared Preferences
                applyCodeBlock
                        .add("\t\t.put$L($S, $L)\n", method, brutal.value(), name);

                // Add fields and methods to respective arrays
                getters.add(getterMethodSpec);
                setters.add(setterMethodSpec);
                fields.add(fieldSpec);
            }

            // Add all the fields
            for (FieldSpec fieldSpec : fields) {
                typeSpec.addField(fieldSpec);
            }

            // Finish building constructor
            MethodSpec constructorSpec = MethodSpec
                    .constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(
                            ClassName.get("android.content", "Context"),
                            "context"
                    )
                    .addStatement("preferences = context.getSharedPreferences($S, 0)", preferencesName)
                    .addCode(constructorCodeBlock.build())
                    .build();

            // Finish building apply method
            MethodSpec applyMethodSpec = MethodSpec
                    .methodBuilder("apply")
                    .addModifiers(Modifier.PUBLIC)
                    .addCode(CodeBlock
                            .builder()
                            .add("preferences\n")
                            .add("\t\t.edit()\n")
                            .add(applyCodeBlock.build())
                            .add("\t\t.apply();\n")
                            .build()
                    )
                    .build();

            // Add all the methods to generated class

            typeSpec.addMethod(constructorSpec);
            typeSpec.addMethod(applyMethodSpec);

            for (MethodSpec getter : getters) {
                typeSpec.addMethod(getter);
            }

            for (MethodSpec setter : setters) {
                typeSpec.addMethod(setter);
            }

            try {
                // Generate the source code and save
                JavaFile
                        .builder("com.flycode.brutalpreferences.generated", typeSpec.build())
                        .build()
                        .writeTo(filer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return false;
    }

    /**
     * This method is called by compiler to know which annotations do we support
     * @return The list of supported annotations
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> supportedAnnotations = new HashSet<>();
        supportedAnnotations.add(BrutalProcessor.class.getCanonicalName());
        supportedAnnotations.add(Brutal.class.getCanonicalName());
        return supportedAnnotations;
    }

    /**
     * Checks if type is supported
     * @param type The type to check
     * @return true if supported, false otherwise
     */
    private boolean isSupported(TypeMirror type) {
        for (TypeMirror supportedType : supportedTypes) {
            if (typeUtils.isSameType(supportedType, type)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets the name needed for Shared Preferences getter and setter generation
     * @param type Type for which to get
     * @return name needed for Shared Preferences getter and setter generation
     */
    private String getMethod(TypeMirror type) {
        for (int index = 0 ; index < supportedTypes.size() ; index++) {
            if (typeUtils.isSameType(supportedTypes.get(index), type)) {
                return preferencesMethods.get(index);
            }
        }

        return null;
    }

    /**
     * Gets the default needed for Shared Preferences getter generation
     * @param type Type for which to get
     * @return default needed for Shared Preferences getter generation
     */
    private String getDefault(TypeMirror type) {
        for (int index = 0 ; index < supportedTypes.size() ; index++) {
            if (typeUtils.isSameType(supportedTypes.get(index), type)) {
                return preferencesDefaults.get(index);
            }
        }

        return null;
    }

    /**
     * Generates getter
     * @param name name to generate getter with
     * @return generated getter name
     */
    private String getSetterName(String name) {
        return "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    /**
     * Generates setter
     * @param name name to generate setter with
     * @return generated setter name
     */
    private String getGetterName(String name) {
        return "get" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}
