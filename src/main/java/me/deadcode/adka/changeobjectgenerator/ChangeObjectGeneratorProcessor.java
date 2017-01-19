package me.deadcode.adka.changeobjectgenerator;

import me.deadcode.adka.changeobjectgenerator.annotation.GenerateChangeObject;
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
import java.util.*;
import java.util.stream.Collectors;

import static me.deadcode.adka.changeobjectgenerator.JavaElements.*;

@SupportedAnnotationTypes("me.deadcode.adka.changeobjectgenerator.annotation.GenerateChangeObject")
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
    private static final String INIT_FROM = "initFrom";
    private static final String TO_ENTITY = "toEntity";

    private Messager messager;
    private boolean firstRound = true;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        this.messager = processingEnv.getMessager();
    }

    //TODO imports vs FQNs
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (firstRound) {
            //TODO clean up - for the most part copied+adapted from BuilderGen
            //TODO replace EVERYWHERE: decapitalize(XY) by a reasonable name (usually paramName derived from className)

            //-----------------PREPARE-----------------
            //TODO optimize the preparation phase?

            //hack: first pass through them and get all FQ class names
            //TODO find a better way to see if the parent class is annotated?
            Set<String> annotatedClasses = roundEnv.getElementsAnnotatedWith(GenerateChangeObject.class)
                    .stream().map(el -> ((TypeElement) el).getQualifiedName().toString()).collect(Collectors.toSet());

            //hack to save the FQN for nested classes (later when getting types from Roaster, the enclosing class is not included in it)
            Map<String, String> nestedClassesFullTypeToRoasterType = new HashMap<>(); //TODO use this or what?
            for (Element el : roundEnv.getElementsAnnotatedWith(GenerateChangeObject.class)) {
                for (Element enclosed : el.getEnclosedElements()) {
                    if ((enclosed.getKind() == ElementKind.ENUM) || (enclosed.getKind() == ElementKind.CLASS) ||
                            (enclosed.getKind() == ElementKind.ANNOTATION_TYPE) || (enclosed.getKind() == ElementKind.INTERFACE)) {

                        String fullName = ((TypeElement) enclosed).getQualifiedName().toString();

                        String beginning = fullName.substring(0, fullName.lastIndexOf('.'));
                        String nameAsReturnedByRoaster = beginning.substring(0, beginning.lastIndexOf('.') + 1) +
                                fullName.substring(fullName.lastIndexOf('.') + 1);

                        nestedClassesFullTypeToRoasterType.put(nameAsReturnedByRoaster, fullName);
                    }
                }
            }

            //find top annotated superclass for each class
            Map<String, String> oneStepUp = new HashMap<>();
            for (Element el : roundEnv.getElementsAnnotatedWith(GenerateChangeObject.class))  {
                String thisClass = ((TypeElement) el).getQualifiedName().toString();
                String parentClass = ((TypeElement) el).getSuperclass().toString();

                if (! annotatedClasses.contains(parentClass)) {
                    parentClass = null;
                }

                oneStepUp.put(thisClass, parentClass);
            }

            Map<String, String> topAnnotatedSuperClass = new HashMap<>();
            for (String thisClass : oneStepUp.keySet()) {
                String topAnnSuperClass = thisClass;
                while (oneStepUp.get(topAnnSuperClass) != null) {
                    topAnnSuperClass = oneStepUp.get(topAnnSuperClass);
                }

                topAnnotatedSuperClass.put(thisClass, topAnnSuperClass);
            }

            //-----------------------------------------


            for (Element c : roundEnv.getElementsAnnotatedWith(GenerateChangeObject.class)) {
                if (c.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
                    //this is not a top-level class but rather a nested class/enum, local or anonymous class; ignore for simplicity
                    messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "ChangeObjectGenerator: Ignoring nested class/enum '" +
                            ((TypeElement) c).getQualifiedName() + "'");
                    continue;
                }

                if (c.getKind() == ElementKind.ENUM) {
                    //ignore enums as well
                    messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "ChangeObjectGenerator: Ignoring enum '" +
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



                    //check if the parent of this class is also annotated with this annotation, i.e. will also get a generated
                    //  changeobject. if so, add the extension clause
                    String parentClass = classs.getSuperclass().toString();
                    if (annotatedClasses.contains(parentClass)) {
                        String parentClassSimpleName = (parentClass.contains(".")) ?
                                parentClass.substring(parentClass.lastIndexOf(".") + 1) : parentClass;
                        generatedJavaClass.setSuperType(parentClassSimpleName + CHANGE_OBJECT);
                    }



                    List<String> isSomethingChanged = new ArrayList<>();
                    if (annotatedClasses.contains(parentClass)) {
                        isSomethingChanged.add(_super() + "." + isXChanged(SOMETHING) + "()");
                    }

                    //TODO clean everything up, introduce local vars, methods, etc., this is very hard to read
                    StringBuilder initFromEntity = new StringBuilder();
                    StringBuilder initFromChangeObject = new StringBuilder();
                    if (annotatedClasses.contains(parentClass)) {
                        initFromEntity.append(_super()).append(".").append(INIT_FROM)
                                .append(simpleFromFQN(oneStepUp.get(classs.getQualifiedName().toString())))
                                .append("(").append(decapitalize(classs.getSimpleName().toString()))
                                .append(")").append(END_COMMAND);
                        initFromChangeObject.append(_super()).append(".").append(INIT_FROM)
                                .append(simpleFromFQN(oneStepUp.get(classs.getQualifiedName().toString())))
                                .append(CHANGE_OBJECT).append("(").append(decapitalize(classs.getSimpleName().toString())).append(CHANGE_OBJECT)
                                .append(")").append(END_COMMAND);
                    }
                    String topAnnClass = topAnnotatedSuperClass.get(classs.getQualifiedName().toString());
                    topAnnClass = topAnnClass.substring(topAnnClass.lastIndexOf('.') + 1);
                    StringBuilder toEntity = new StringBuilder();
                    for (FieldSource<JavaClassSource> attribute : javaClass.getFields()) {
                        //ignore static and final fields //TODO or should we?
                        if (attribute.isStatic()) {
                            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "ChangeObjectGenerator: Ignoring static attribute '" +
                                    attribute.getName() + "'");
                            continue;
                        }

                        if (attribute.isFinal()) {
                            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "ChangeObjectGenerator: Ignoring final attribute '" +
                                    attribute.getName() + "'");
                            continue;
                        }

                        //field
                        generatedJavaClass.addField()
                                .setPrivate()
                                .setType(ValueWrapper.class.getCanonicalName() + "<" + getObjectTypeFQN(attribute.getType()) + ">")
                                .setName(attribute.getName())
                                .setLiteralInitializer(_new(ValueWrapper.class.getSimpleName() + DIAMOND));
                        generatedJavaClass.addImport(attribute.getType());

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

                        initFromChangeObject.append(_this()).append(".").append(attribute.getName()).append(" = ")
                                .append(_new(ValueWrapper.class.getSimpleName() + DIAMOND,
                                        decapitalize(generatedJavaClass.getName()) + "." + _get(attribute.getName())))
                                .append(END_COMMAND);

                        initFromEntity.append(_this()).append(".").append(attribute.getName()).append(" = ")
                                .append(_new(ValueWrapper.class.getSimpleName() + DIAMOND,
                                        decapitalize(classs.getSimpleName().toString()) + "." + _get(attribute.getName())))
                                .append(END_COMMAND);

                        toEntity.append(decapitalize(annotatedClasses.contains(parentClass) ? classs.getSimpleName().toString() : topAnnClass))
                                .append(".").append(_set(attribute.getName(), _get(attribute)));
                    }

                    //isSomethingChanged
                    generatedJavaClass.addMethod()
                            .setPublic()
                            .setReturnType(BOOL)
                            .setName(isXChanged(SOMETHING))
                            .setBody(_return(isSomethingChanged.stream().collect(Collectors.joining(" || "))));
                    //if this is a subclass
                    if (annotatedClasses.contains(parentClass)) {
                        generatedJavaClass.getMethod(isXChanged(SOMETHING))
                                .addAnnotation(Override.class);
                    }

                    //initFromChangeObject
                    generatedJavaClass.addMethod()
                            .setPublic()
                            .setReturnTypeVoid()
                            .setName(INIT_FROM + generatedJavaClass.getName())
                            .setBody(initFromChangeObject.toString())
                            .addParameter(generatedJavaClass.getName(), decapitalize(generatedJavaClass.getName()));

                    //initFromEntity
                    generatedJavaClass.addMethod()
                            .setPublic()
                            .setReturnTypeVoid()
                            .setName(INIT_FROM + classs.getSimpleName())
                            .setBody(initFromEntity.toString())
                            .addParameter(classs.getSimpleName().toString(), decapitalize(classs.getSimpleName().toString()));

                    //toEntity
                    String toEntityBody;
                    //if this is a subclass
                    if (annotatedClasses.contains(parentClass)) {
                        toEntityBody =_super() + "." + TO_ENTITY + "(" + decapitalize(topAnnClass) + ")" + END_COMMAND;
                        toEntityBody += _if(_instanceof(decapitalize(topAnnClass), classs.getSimpleName().toString()),
                                classs.getSimpleName().toString() + " " + decapitalize(classs.getSimpleName().toString()) + " = " +
                                        _cast(decapitalize(topAnnClass), classs.getSimpleName().toString()) + END_COMMAND +
                                toEntity.toString());
                    } else {
                        toEntityBody = toEntity.toString();
                    }

                    generatedJavaClass.addMethod()
                            .setPublic()
                            .setReturnTypeVoid()
                            .setName(TO_ENTITY)
                            .setBody(toEntityBody)
                            .addParameter(topAnnotatedSuperClass.get(classs.getQualifiedName().toString()),
                                    decapitalize(topAnnClass));
                    //if this is a subclass
                    if (annotatedClasses.contains(parentClass)) {
                        generatedJavaClass.getMethod(TO_ENTITY, topAnnotatedSuperClass.get(classs.getQualifiedName().toString()))
                                .addAnnotation(Override.class);
                    }

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

    private String paramFromFQNClass(String fqnClassName) {
        return decapitalize(simpleFromFQN(fqnClassName));
    }

    private String simpleFromFQN(String fqClassName) {
        return fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
    }

    private String isXChanged(String x) {
        return String.format(IS_X_CHANGED, capitalize(x));
    }
}
