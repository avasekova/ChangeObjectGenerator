package me.deadcode.adka.entitychangegen;

import me.deadcode.adka.entitychangegen.annotation.GenerateChangeObject;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.Type;
import org.jboss.forge.roaster.model.source.FieldSource;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.JavaDocSource;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static me.deadcode.adka.entitychangegen.JavaElements.*;

@SupportedAnnotationTypes("me.deadcode.adka.entitychangegen.annotation.GenerateChangeObject")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class ChangeObjectGeneratorProcessor extends AbstractProcessor {

    private static final String NOTE_GENERATED_CODE = "Note: generated code. All changes will be undone on the next build " +
            "as long as the originator class is annotated with @GenerateChangeObject.";

    private static final String CLASSES_PATH_PREFIX = "src" + File.separator + "main" + File.separator + "java" + File.separator;
    private static final String CHANGE_OBJECT = "ChangeObject";
    private static final String DOT_JAVA = ".java";

    private static final String DIAMOND = "<>";
    private static final String VALUE = "value";
    private static final String GET = "get";
    private static final String SET = "set";
    private static final String BOOL = "boolean";
    private static final String IS_X_CHANGED = "is%sChanged";
    private static final String CHANGED = "changed";
    private static final String SOMETHING = "something";
    private static final String FROM = "from";
    private static final String TO = "to";

    private Messager messager;
    private boolean firstRound = true;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        this.messager = processingEnv.getMessager();
    }

    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (firstRound) {
            //TODO clean up - for the most part copied+adapted from BuilderGen


            for (Element c : roundEnv.getElementsAnnotatedWith(GenerateChangeObject.class)) {
                if (c.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
                    //this is not a top-level class but rather a nested class/enum, local or anonymous class; ignore for simplicity
                    messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "BuilderGenerator: Ignoring nested class/enum '" +
                            ((TypeElement) c).getQualifiedName() + "'");
                    continue;
                }

                if (c.getKind() == ElementKind.ENUM) {
                    //ignore enums as well
                    messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "BuilderGenerator: Ignoring enum '" +
                            ((TypeElement) c).getQualifiedName() + "'");
                    continue;
                }


                TypeElement classs = (TypeElement) c;
                String packageAndClass = classs.getQualifiedName().toString();
                String pathToClass = packageAndClass.replace('.', File.separatorChar);
                try {
                    //read from here
                    File classFile = new File(CLASSES_PATH_PREFIX + pathToClass + DOT_JAVA);
                    JavaClassSource javaClass = Roaster.parse(JavaClassSource.class, classFile);

                    //output here
                    //TODO remove the class if already generated
                    File generatedClassFile = new File(CLASSES_PATH_PREFIX + pathToClass + CHANGE_OBJECT + DOT_JAVA);
                    JavaClassSource generatedJavaClass = Roaster.create(JavaClassSource.class);
                    generatedJavaClass.setPackage(packageAndClass.substring(0, packageAndClass.lastIndexOf('.')))
                            .setName(classs.getSimpleName().toString() + CHANGE_OBJECT);


                    List<String> isSomethingChanged = new ArrayList<>();
                    StringBuilder fromEntity = new StringBuilder();
                    StringBuilder fromChangeObject = new StringBuilder();
                    StringBuilder toEntity = new StringBuilder();
                    toEntity.append(classs.getSimpleName().toString()).append(" ").append(decapitalize(classs.getSimpleName().toString()))
                            .append(" = ").append(_new(classs.getSimpleName().toString())).append(END_COMMAND);
                    for (FieldSource<JavaClassSource> attribute : javaClass.getFields()) {
                        //ignore static and final fields //TODO or should we?
                        if (attribute.isStatic()) {
                            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "BuilderGenerator: Ignoring static attribute '" +
                                    attribute.getName() + "'");
                            continue;
                        }

                        if (attribute.isFinal()) {
                            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "BuilderGenerator: Ignoring final attribute '" +
                                    attribute.getName() + "'");
                            continue;
                        }

                        //field
                        generatedJavaClass.addField()
                                .setPrivate()
                                .setType(ValueWrapper.class.getCanonicalName() + "<" + getObjectTypeFQN(attribute.getType()) + ">")
                                .setName(attribute.getName())
                                .setLiteralInitializer(_new(ValueWrapper.class.getSimpleName() + DIAMOND));

                        //methods
                        generatedJavaClass.addMethod()
                                .setPublic()
                                .setReturnType(getObjectTypeFQN(attribute.getType()))
                                .setName(GET + capitalize(attribute.getName()))
                                .setBody(_return(attribute.getName() + "." + _get(VALUE)));

                        generatedJavaClass.addMethod()
                                .setPublic()
                                .setReturnTypeVoid()
                                .setName(SET + capitalize(attribute.getName()))
                                .setBody(_this() + "." + attribute.getName() + "." + _set(VALUE, attribute.getName()))
                                .addParameter(attribute.getType().getQualifiedNameWithGenerics(), attribute.getName());

                        String isChanged = attribute.getName() + "." + _is(CHANGED);
                        generatedJavaClass.addMethod()
                                .setPublic()
                                .setReturnType(BOOL)
                                .setName(isXChanged(attribute.getName()))
                                .setBody(_return(isChanged));

                        isSomethingChanged.add(isChanged);

                        fromChangeObject.append(_this()).append(".").append(attribute.getName()).append(" = ")
                                .append(_new(ValueWrapper.class.getSimpleName() + DIAMOND,
                                        decapitalize(generatedJavaClass.getName()) + "." + _get(attribute.getName())))
                                .append(END_COMMAND);

                        fromEntity.append(_this()).append(".").append(attribute.getName()).append(" = ")
                                .append(_new(ValueWrapper.class.getSimpleName() + DIAMOND,
                                        decapitalize(classs.getSimpleName().toString()) + "." + _get(attribute.getName())))
                                .append(END_COMMAND);

                        toEntity.append(decapitalize(classs.getSimpleName().toString())).append(".")
                                .append(_set(attribute.getName(), _get(attribute)));
                    }

                    //isSomethingChanged
                    generatedJavaClass.addMethod()
                            .setPublic()
                            .setReturnType(BOOL)
                            .setName(isXChanged(SOMETHING))
                            .setBody(_return(isSomethingChanged.stream().collect(Collectors.joining(" || "))));

                    //fromChangeObject
                    generatedJavaClass.addMethod()
                            .setPublic()
                            .setReturnTypeVoid()
                            .setName(FROM + generatedJavaClass.getName())
                            .setBody(fromChangeObject.toString())
                            .addParameter(generatedJavaClass.getName(), decapitalize(generatedJavaClass.getName()));

                    //fromEntity
                    generatedJavaClass.addMethod()
                            .setPublic()
                            .setReturnTypeVoid()
                            .setName(FROM + classs.getSimpleName())
                            .setBody(fromEntity.toString())
                            .addParameter(classs.getSimpleName().toString(), decapitalize(classs.getSimpleName().toString()));

                    //toEntity
                    toEntity.append(_return(decapitalize(classs.getSimpleName().toString())));
                    generatedJavaClass.addMethod()
                            .setPublic()
                            .setReturnType(classs.getSimpleName().toString())
                            .setName(TO + classs.getSimpleName())
                            .setBody(toEntity.toString());

                    //add the JavaDoc
                    JavaDocSource changeObjectJavadoc = generatedJavaClass.getJavaDoc();
                    changeObjectJavadoc.setText(NOTE_GENERATED_CODE);

                    try (BufferedWriter bw = new BufferedWriter(new FileWriter(generatedClassFile));
                         InputStream is = ChangeObjectGeneratorProcessor.class.getClassLoader()
                                 .getResourceAsStream("options.properties")) {
                        Properties formattingProperties = new Properties();
                        formattingProperties.load(is);
                        bw.write(Roaster.format(formattingProperties, generatedJavaClass.toString()));
                    }

                } catch (IOException e) {
                    messager.printMessage(Diagnostic.Kind.ERROR, e.toString());
                }
            }

            firstRound = false;
        }

        return true;
    }


    private String getObjectTypeFQN(Type<JavaClassSource> attributeType) {
        if (attributeType.isPrimitive()) {
            if (attributeType.isArray()) {
                return attributeType.getName();
            }

            //else "boxing"
            switch (attributeType.getName()) { //TODO is there a better way?
                case BOOL:
                case "byte":
                case "double":
                case "float":
                case "long":
                case "short":
                    return capitalize(attributeType.getName());
                case "char":
                    return Character.class.getSimpleName();
                case "int":
                    return Integer.class.getSimpleName();
                default:
                    return Object.class.getSimpleName();
            }
        } else {
            return attributeType.getQualifiedNameWithGenerics();
        }
    }

    private static String capitalize(String attributeName) {
        return attributeName.substring(0,1).toUpperCase() + (attributeName.length() == 1 ? "" : attributeName.substring(1));
    }

    private String decapitalize(String className) {
        return className.substring(0,1).toLowerCase() + (className.length() == 1 ? "" : className.substring(1));
    }

    private String isXChanged(String x) {
        return String.format(IS_X_CHANGED, capitalize(x));
    }
}
