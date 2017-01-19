package me.deadcode.adka.changeobjectgenerator;

import org.jboss.forge.roaster.model.source.FieldSource;
import org.jboss.forge.roaster.model.source.JavaClassSource;

//TODO create a common 'utils' repo? this is copy+paste from BuilderGen
public class JavaElements {

    public static final String END_COMMAND = ";" + System.lineSeparator();

    public static String _this() {
        return "this";
    }

    public static String _super() {
        return "super";
    }

    public static String _notNull(String object) {
        return object + " != null";
    }

    public static String _new(String type) {
        return _new(type, "");
    }

    public static String _new(String type, String params) { return "new " + type + "(" + params + ")"; }

    public static String _return(String object) {
        return "return " + object + END_COMMAND;
    }

    public static String _instanceof(String object, String type) {
        return object + " instanceof " + type;
    }

    public static String _cast(String object, String type) {
        return "(" + type + ") " + object;
    }

    public static String _get(String object) {
        return "get" + capitalize(object) + "()";
    }

    public static String _get(FieldSource<JavaClassSource> attribute) {
        return (attribute.getType().getSimpleName().toLowerCase().equals("boolean") ? "is" : "get") +
                capitalize(attribute.getName()) + "()";
    }

    public static String _is(String object) {
        return "is" + capitalize(object) + "()";
    }

    public static String _set(String object) {
        return _set(object, object);
    }

    public static String _set(String object, String parameter) {
        return "set" + capitalize(object) + "(" + parameter + ")" + END_COMMAND;
    }

    public static String _add(String object) {
        return "add(" + object + ");";
    }

    public static String _addAll(String collection) {
        return "addAll(" + collection + ");";
    }

    public static String _if(String predicate, String thenWhat) {
        return "if (" + predicate + ") {" + System.lineSeparator() +
                thenWhat + System.lineSeparator() + "}";
    }

    public static String _if(String predicate, String thenWhat, String elseWhat) {
        return "if (" + predicate + ") {" + System.lineSeparator() +
                thenWhat + System.lineSeparator() + "} else {" + System.lineSeparator() +
                elseWhat + System.lineSeparator() + "}";
    }


    public static String capitalize(String attributeName) {
        return attributeName.substring(0,1).toUpperCase() + (attributeName.length() == 1 ? "" : attributeName.substring(1));
    }

}
