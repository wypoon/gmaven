package org.codehaus.gmaven.runtime.v1_8;

import groovyjarjarasm.asm.Opcodes;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.Verifier;
import org.codehaus.groovy.control.ResolveVisitor;
import org.codehaus.groovy.tools.Utilities;

import java.io.*;
import java.util.*;


public class JavaStubGenerator {
    private boolean java5 = false;
    private boolean requireSuperResolved = false;
    private File outputPath;
    private List<String> toCompile = new ArrayList<String>();
    private ArrayList<MethodNode> propertyMethods = new ArrayList<MethodNode>();
    private Map<String, MethodNode> propertyMethodsWithSigs = new HashMap<String, MethodNode>();
    private ArrayList<ConstructorNode> constructors = new ArrayList<ConstructorNode>();
    private ModuleNode currentModule;

    public JavaStubGenerator(final File outputPath, final boolean requireSuperResolved, final boolean java5) {
        this.outputPath = outputPath;
        this.requireSuperResolved = requireSuperResolved;
        this.java5 = java5;
        outputPath.mkdirs();
    }

    public JavaStubGenerator(final File outputPath) {
        this(outputPath, false, false);
    }

    private void mkdirs(File parent, String relativeFile) {
        int index = relativeFile.lastIndexOf('/');
        if (index == -1) return;
        File dir = new File(parent, relativeFile.substring(0, index));
        dir.mkdirs();
    }

    public void generateClass(ClassNode classNode) throws FileNotFoundException {
        // Only attempt to render our self if our super-class is resolved, else wait for it
        if (requireSuperResolved && !classNode.getSuperClass().isResolved()) {
            return;
        }

        // owner should take care for us
        if (classNode instanceof InnerClassNode)
            return;

        // don't generate stubs for private classes, as they are only visible in the same file
        if ((classNode.getModifiers() & Opcodes.ACC_PRIVATE) != 0) return;

        String fileName = classNode.getName().replace('.', '/');
        mkdirs(outputPath, fileName);
        toCompile.add(fileName);

        File file = new File(outputPath, fileName + ".java");
        FileOutputStream fos = new FileOutputStream(file);
        PrintWriter out = new PrintWriter(fos);

        try {
            String packageName = classNode.getPackageName();
            if (packageName != null) {
                out.println("package " + packageName + ";\n");
            }

            printImports(out, classNode);
            printClassContents(out, classNode);

        } finally {
            try {
                out.close();
            } catch (Exception e) {
                // ignore
            }
            try {
                fos.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void printClassContents(PrintWriter out, ClassNode classNode) throws FileNotFoundException {
        if (classNode instanceof InnerClassNode && ((InnerClassNode) classNode).isAnonymous()) {
            // if it is an anonymous inner class, don't generate the stub code for it.
            return;
        }
        try {
            Verifier verifier = new Verifier() {
                public void addCovariantMethods(ClassNode cn) {}
                protected void addTimeStamp(ClassNode node) {}
                protected void addInitialization(ClassNode node) {}
                protected void addPropertyMethod(MethodNode method) {
                    doAddMethod(method);
                }
                protected void addReturnIfNeeded(MethodNode node) {}
                protected void addMethod(ClassNode node, boolean shouldBeSynthetic, String name, int modifiers, ClassNode returnType, Parameter[] parameters, ClassNode[] exceptions, Statement code) {
                    doAddMethod(new MethodNode(name, modifiers, returnType, parameters, exceptions, code));
                }

                protected void addConstructor(Parameter[] newParams, ConstructorNode ctor, Statement code, ClassNode node) {
                    if (code instanceof ExpressionStatement) {//GROOVY-4508
                        Statement temp = code;
                        code = new BlockStatement();
                        ((BlockStatement) code).addStatement(temp);
                    }
                    ConstructorNode ctrNode = new ConstructorNode(ctor.getModifiers(), newParams, ctor.getExceptions(), code);
                    ctrNode.setDeclaringClass(node);
                    constructors.add(ctrNode);
                }

                protected void addDefaultParameters(DefaultArgsAction action, MethodNode method) {
                    final Parameter[] parameters = method.getParameters();
                    final Expression[] saved = new Expression[parameters.length];
                    for (int i = 0; i < parameters.length; i++) {
                        if (parameters[i].hasInitialExpression())
                            saved[i] = parameters[i].getInitialExpression();
                    }
                    super.addDefaultParameters(action, method);
                    for (int i = 0; i < parameters.length; i++) {
                        if (saved[i] != null)
                            parameters[i].setInitialExpression(saved[i]);
                    }
                }

                private void doAddMethod(MethodNode method) {
                    String sig = method.getTypeDescriptor();

                    if (propertyMethodsWithSigs.containsKey(sig)) return;

                    propertyMethods.add(method);
                    propertyMethodsWithSigs.put(sig, method);
                }

                @Override
                protected void addDefaultConstructor(ClassNode node) {
                    // not required for stub generation
                }
            };
            verifier.visitClass(classNode);
            currentModule = classNode.getModule();

            boolean isInterface = classNode.isInterface();
            boolean isEnum = (classNode.getModifiers() & Opcodes.ACC_ENUM) != 0;
            boolean isAnnotationDefinition = classNode.isAnnotationDefinition();
            printAnnotations(out, classNode);
            printModifiers(out, classNode.getModifiers()
                    & ~(isInterface ? Opcodes.ACC_ABSTRACT : 0));

            if (isInterface) {
                if (isAnnotationDefinition) {
                    out.print("@");
                }
                out.print("interface ");
            } else if (isEnum) {
                out.print("enum ");
            } else {
                out.print("class ");
            }

            String className = classNode.getNameWithoutPackage();
            if (classNode instanceof InnerClassNode)
                className = className.substring(className.lastIndexOf("$") + 1);
            out.println(className);
            printGenericsBounds(out, classNode, true);

            ClassNode superClass = classNode.getUnresolvedSuperClass(false);

            if (!isInterface && !isEnum) {
                out.print("  extends ");
                printType(out, superClass);
            }

            ClassNode[] interfaces = classNode.getInterfaces();
            if (interfaces != null && interfaces.length > 0 && !isAnnotationDefinition) {
                if (isInterface) {
                    out.println("  extends");
                } else {
                    out.println("  implements");
                }
                for (int i = 0; i < interfaces.length - 1; ++i) {
                    out.print("    ");
                    printType(out, interfaces[i]);
                    out.print(",");
                }
                out.print("    ");
                printType(out, interfaces[interfaces.length - 1]);
            }
            out.println(" {");

            printFields(out, classNode);
            printMethods(out, classNode, isEnum);

            for (Iterator<InnerClassNode> inner = classNode.getInnerClasses(); inner.hasNext(); ) {
                // GROOVY-4004: Clear the methods from the outer class so that they don't get duplicated in inner ones
                propertyMethods.clear();
                propertyMethodsWithSigs.clear();
                constructors.clear();
                printClassContents(out, inner.next());
            }

            out.println("}");
        } finally {
            propertyMethods.clear();
            propertyMethodsWithSigs.clear();
            constructors.clear();
            currentModule = null;
        }
    }

    private void printMethods(PrintWriter out, ClassNode classNode, boolean isEnum) {
        if (!isEnum) printConstructors(out, classNode);

        @SuppressWarnings("unchecked")
        List<MethodNode> methods = (List) propertyMethods.clone();
        methods.addAll(classNode.getMethods());
        for (MethodNode method : methods) {
            if (isEnum && method.isSynthetic()) {
                // skip values() method and valueOf(String)
                String name = method.getName();
                Parameter[] params = method.getParameters();
                if (name.equals("values") && params.length == 0) continue;
                if (name.equals("valueOf") &&
                        params.length == 1 &&
                        params[0].getType().equals(ClassHelper.STRING_TYPE)) {
                    continue;
                }
            }
            printMethod(out, classNode, method);
        }
    }

    private void printConstructors(PrintWriter out, ClassNode classNode) {
        @SuppressWarnings("unchecked")
        List<ConstructorNode> constrs = (List<ConstructorNode>) constructors.clone();
        if (constrs != null) {
            constrs.addAll(classNode.getDeclaredConstructors());
            for (ConstructorNode constr : constrs) {
                printConstructor(out, classNode, constr);
            }
        }
    }

    private void printFields(PrintWriter out, ClassNode classNode) {
        boolean isInterface = classNode.isInterface();
        List<FieldNode> fields = classNode.getFields();
        if (fields == null) return;
        List<FieldNode> enumFields = new ArrayList<FieldNode>(fields.size());
        List<FieldNode> normalFields = new ArrayList<FieldNode>(fields.size());
        for (FieldNode field : fields) {
            boolean isSynthetic = (field.getModifiers() & Opcodes.ACC_SYNTHETIC) != 0;
            if (field.isEnum()) {
                enumFields.add(field);
            } else if (!isSynthetic) {
                normalFields.add(field);
            }
        }
        printEnumFields(out, enumFields);
        for (FieldNode normalField : normalFields) {
            printField(out, normalField, isInterface);
        }
    }

    private void printEnumFields(PrintWriter out, List<FieldNode> fields) {
        if (fields.size() == 0) return;
        boolean first = true;
        for (FieldNode field : fields) {
            if (!first) {
                out.print(", ");
            } else {
                first = false;
            }
            out.print(field.getName());
        }
        out.println(";");
    }

    private void printField(PrintWriter out, FieldNode fieldNode, boolean isInterface) {
        if ((fieldNode.getModifiers() & Opcodes.ACC_PRIVATE) != 0) return;
        printAnnotations(out, fieldNode);
        if (!isInterface) {
            printModifiers(out, fieldNode.getModifiers());
        }

        ClassNode type = fieldNode.getType();
        printType(out, type);

        out.print(" ");
        out.print(fieldNode.getName());
        if (isInterface || (fieldNode.getModifiers() & Opcodes.ACC_FINAL) != 0) {
            out.print(" = ");
            Expression valueExpr = fieldNode.getInitialValueExpression();
            if (valueExpr instanceof ConstantExpression) {
                valueExpr = Verifier.transformToPrimitiveConstantIfPossible((ConstantExpression) valueExpr);
            }
            if (valueExpr instanceof ConstantExpression
                    && ClassHelper.isStaticConstantInitializerType(valueExpr.getType())
                    && valueExpr.getType().equals(fieldNode.getType())) {
                if (ClassHelper.STRING_TYPE.equals(valueExpr.getType())) {
                    // GROOVY-5150 : Initialize value with a dummy constant so that Java cross compiles correctly
                    // -- old methods --
//                    out.print("\"\"");
//                    out.print("\""+valueExpr.getText().replaceAll("\\\"", "\\\\\"")+"\"");
                    // -- new method --
                    out.print("\"" + valueExpr.getText().replaceAll("\\\\", "\\\\\\\\")
                            .replaceAll("\\\"", "\\\\\"").replaceAll("\b", "\\\\b").replaceAll("\n", "\\\\n")
                            .replaceAll("\r", "\\\\r").replaceAll("\t", "\\\\t") + "\"");
                } else if (ClassHelper.char_TYPE.equals(valueExpr.getType())) {
                    out.print("'"+valueExpr.getText()+"'");
                } else {
                    out.print(valueExpr.getText());
                }
            } else if (ClassHelper.isPrimitiveType(type)) {
                String val = type == ClassHelper.boolean_TYPE ? "false" : "0";
                out.print("new " + ClassHelper.getWrapper(type) + "((" + type + ")" + val + ")");
            } else {
                out.print("null");
            }
        }
        out.println(";");
    }

    private ConstructorCallExpression getConstructorCallExpression(ConstructorNode constructorNode) {
        Statement code = constructorNode.getCode();
        if (!(code instanceof BlockStatement))
            return null;

        BlockStatement block = (BlockStatement) code;
        List stats = block.getStatements();
        if (stats == null || stats.size() == 0)
            return null;

        Statement stat = (Statement) stats.get(0);
        if (!(stat instanceof ExpressionStatement))
            return null;

        Expression expr = ((ExpressionStatement) stat).getExpression();
        if (!(expr instanceof ConstructorCallExpression))
            return null;

        return (ConstructorCallExpression) expr;
    }

    private void printConstructor(PrintWriter out, ClassNode clazz, ConstructorNode constructorNode) {
        printAnnotations(out, constructorNode);
        // printModifiers(out, constructorNode.getModifiers());

        out.print("public "); // temporary hack
        String className = clazz.getNameWithoutPackage();
        if (clazz instanceof InnerClassNode)
            className = className.substring(className.lastIndexOf("$") + 1);
        out.println(className);

        printParams(out, constructorNode);

        ConstructorCallExpression constrCall = getConstructorCallExpression(constructorNode);
        if (constrCall == null || !constrCall.isSpecialCall()) {
            out.println(" {}");
        } else {
            out.println(" {");
            printSpecialConstructorArgs(out, constructorNode, constrCall);
            out.println("}");
        }
    }

    private Parameter[] selectAccessibleConstructorFromSuper(ConstructorNode node) {
        ClassNode type = node.getDeclaringClass();
        ClassNode superType = type.getSuperClass();

        for (ConstructorNode c : superType.getDeclaredConstructors()) {
            // Only look at things we can actually call
            if (c.isPublic() || c.isProtected()) {
                return c.getParameters();
            }
        }

        // fall back for parameterless constructor
        if (superType.isPrimaryClassNode()) {
            return Parameter.EMPTY_ARRAY;
        }

        return null;
    }

    private void printSpecialConstructorArgs(PrintWriter out, ConstructorNode node, ConstructorCallExpression constrCall) {
        // Select a constructor from our class, or super-class which is legal to call,
        // then write out an invoke w/nulls using casts to avoid ambiguous crapo

        Parameter[] params = selectAccessibleConstructorFromSuper(node);
        if (params != null) {
            out.print("super (");

            for (int i = 0; i < params.length; i++) {
                printDefaultValue(out, params[i].getType());
                if (i + 1 < params.length) {
                    out.print(", ");
                }
            }

            out.println(");");
            return;
        }

        // Otherwise try the older method based on the constructor's call expression
        Expression arguments = constrCall.getArguments();

        if (constrCall.isSuperCall()) {
            out.print("super(");
        } else {
            out.print("this(");
        }

        // Else try to render some arguments
        if (arguments instanceof ArgumentListExpression) {
            ArgumentListExpression argumentListExpression = (ArgumentListExpression) arguments;
            List<Expression> args = argumentListExpression.getExpressions();

            for (Expression arg : args) {
                if (arg instanceof ConstantExpression) {
                    ConstantExpression expression = (ConstantExpression) arg;
                    Object o = expression.getValue();

                    if (o instanceof String) {
                        out.print("(String)null");
                    } else {
                        out.print(expression.getText());
                    }
                } else {
                    ClassNode type = getConstructorArgumentType(arg, node);
                    printDefaultValue(out, type);
                }

                if (arg != args.get(args.size() - 1)) {
                    out.print(", ");
                }
            }
        }

        out.println(");");
    }

    private ClassNode getConstructorArgumentType(Expression arg, ConstructorNode node) {
        if (!(arg instanceof VariableExpression)) return arg.getType();
        VariableExpression vexp = (VariableExpression) arg;
        String name = vexp.getName();
        for (Parameter param : node.getParameters()) {
            if (param.getName().equals(name)) {
                return param.getType();
            }
        }
        return vexp.getType();
    }

    private void printMethod(PrintWriter out, ClassNode clazz, MethodNode methodNode) {
        if (methodNode.getName().equals("<clinit>")) return;
        if (methodNode.isPrivate() || !Utilities.isJavaIdentifier(methodNode.getName())) return;
        if (methodNode.isSynthetic() && methodNode.getName().equals("$getStaticMetaClass")) return;

        printAnnotations(out, methodNode);
        if (!clazz.isInterface()) printModifiers(out, methodNode.getModifiers());

        printGenericsBounds(out, methodNode.getGenericsTypes());
        out.print(" ");
        printType(out, methodNode.getReturnType());
        out.print(" ");
        out.print(methodNode.getName());

        printParams(out, methodNode);

        ClassNode[] exceptions = methodNode.getExceptions();
        for (int i = 0; i < exceptions.length; i++) {
            ClassNode exception = exceptions[i];
            if (i == 0) {
                out.print("throws ");
            } else {
                out.print(", ");
            }
            printType(out, exception);
        }

        if ((methodNode.getModifiers() & Opcodes.ACC_ABSTRACT) != 0) {
            out.println(";");
        } else {
            out.print(" { ");
            ClassNode retType = methodNode.getReturnType();
            printReturn(out, retType);
            out.println("}");
        }
    }

    private void printReturn(PrintWriter out, ClassNode retType) {
        String retName = retType.getName();
        if (!retName.equals("void")) {
            out.print("return ");

            printDefaultValue(out, retType);

            out.print(";");
        }
    }

    private void printDefaultValue(PrintWriter out, ClassNode type) {
        if (type.redirect() != ClassHelper.OBJECT_TYPE && type.redirect() != ClassHelper.boolean_TYPE) {
            out.print("(");
            printType(out, type);
            out.print(")");
        }

        if (ClassHelper.isPrimitiveType(type)) {
            if (type == ClassHelper.boolean_TYPE) {
                out.print("false");
            } else {
                out.print("0");
            }
        } else {
            out.print("null");
        }
    }

    private void printType(PrintWriter out, ClassNode type) {
        if (type.isArray()) {
            printType(out, type.getComponentType());
            out.print("[]");
        } else if (java5 && type.isGenericsPlaceHolder()) {
            out.print(type.getGenericsTypes()[0].getName());
        } else {
            printGenericsBounds(out, type, false);
        }
    }

    private void printTypeName(PrintWriter out, ClassNode type) {
        if (ClassHelper.isPrimitiveType(type)) {
            if (type == ClassHelper.boolean_TYPE) {
                out.print("boolean");
            } else if (type == ClassHelper.char_TYPE) {
                out.print("char");
            } else if (type == ClassHelper.int_TYPE) {
                out.print("int");
            } else if (type == ClassHelper.short_TYPE) {
                out.print("short");
            } else if (type == ClassHelper.long_TYPE) {
                out.print("long");
            } else if (type == ClassHelper.float_TYPE) {
                out.print("float");
            } else if (type == ClassHelper.double_TYPE) {
                out.print("double");
            } else if (type == ClassHelper.byte_TYPE) {
                out.print("byte");
            } else {
                out.print("void");
            }
        } else {
            String name = type.getName();
            // check for an alias
            ClassNode alias = currentModule.getImportType(name);
            if (alias != null) name = alias.getName();
            out.print(name.replace('$', '.'));
        }
    }

    private void printGenericsBounds(PrintWriter out, ClassNode type, boolean skipName) {
        if (!skipName) printTypeName(out, type);
        if (!java5) return;
        if (!ClassHelper.isCachedType(type)) {
            printGenericsBounds(out, type.getGenericsTypes());
        }
    }

    private void printGenericsBounds(PrintWriter out, GenericsType[] genericsTypes) {
        if (genericsTypes == null || genericsTypes.length == 0) return;
        out.print('<');
        for (int i = 0; i < genericsTypes.length; i++) {
            if (i != 0) out.print(", ");
            out.print(genericsTypes[i].toString().replace("$","."));
        }
        out.print('>');
    }

    private void printParams(PrintWriter out, MethodNode methodNode) {
        out.print("(");
        Parameter[] parameters = methodNode.getParameters();
        if (parameters != null && parameters.length != 0) {
            int lastIndex = parameters.length - 1;
            boolean vararg = parameters[lastIndex].getType().isArray();
            for (int i = 0; i != parameters.length; ++i) {
                if (i == lastIndex && vararg) {
                    printType(out, parameters[i].getType().getComponentType());
                    out.print("...");
                } else {
                    printType(out, parameters[i].getType());
                }
                out.print(" ");
                out.print(parameters[i].getName());
                if (i + 1 < parameters.length) {
                    out.print(", ");
                }
            }
        }
        out.print(")");
    }

    private void printAnnotations(PrintWriter out, AnnotatedNode annotated) {
        if (!java5) return;
        for (AnnotationNode annotation : annotated.getAnnotations()) {
            printAnnotation(out, annotation);
        }
    }

    private void printAnnotation(PrintWriter out, AnnotationNode annotation) {
        out.print("@" + annotation.getClassNode().getName().replace("$",".") + "(");
        boolean first = true;
        Map<String, Expression> members = annotation.getMembers();
        for (String key : members.keySet()) {
            if (first) first = false;
            else out.print(", ");
            out.print(key + "=" + getAnnotationValue(members.get(key)));
        }
        out.print(") ");
    }

    private String getAnnotationValue(Object memberValue) {
        String val = "null";
        if (memberValue instanceof ListExpression) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            ListExpression le = (ListExpression) memberValue;
            for (Expression e : le.getExpressions()) {
                if (first) first = false;
                else sb.append(",");
                sb.append(getAnnotationValue(e));
            }
            sb.append("}");
            val = sb.toString();
        } else if (memberValue instanceof ConstantExpression) {
            ConstantExpression ce = (ConstantExpression) memberValue;
            Object constValue = ce.getValue();
            if (constValue instanceof AnnotationNode) {
                StringWriter writer = new StringWriter();
                PrintWriter out = new PrintWriter(writer);
                printAnnotation(out, (AnnotationNode) constValue);
                val = writer.toString();
            } else if (constValue instanceof Number || constValue instanceof Boolean)
                val = constValue.toString();
            else
                val = "\"" + escapeStringAnnotationValue(constValue.toString()) + "\"";
        } else if (memberValue instanceof PropertyExpression || memberValue instanceof VariableExpression) {
            // assume must be static class field or enum value or class that Java can resolve
            val = ((Expression) memberValue).getText().replace("$",".");
        } else if (memberValue instanceof ClosureExpression) {
            // annotation closure; replaced with this specific class literal to cover the
            // case where annotation type uses Class<? extends Closure> for the closure's type
            val = "groovy.lang.Closure.class";
        } else if (memberValue instanceof ClassExpression) {
            val = ((Expression) memberValue).getText() + ".class";
        }
        return val;
    }

    private void printModifiers(PrintWriter out, int modifiers) {
        if ((modifiers & Opcodes.ACC_PUBLIC) != 0)
            out.print("public ");

        if ((modifiers & Opcodes.ACC_PROTECTED) != 0)
            out.print("protected ");

        if ((modifiers & Opcodes.ACC_PRIVATE) != 0)
            out.print("private ");

        if ((modifiers & Opcodes.ACC_STATIC) != 0)
            out.print("static ");

        if ((modifiers & Opcodes.ACC_SYNCHRONIZED) != 0)
            out.print("synchronized ");

        if ((modifiers & Opcodes.ACC_FINAL) != 0)
            out.print("final ");

        if ((modifiers & Opcodes.ACC_ABSTRACT) != 0)
            out.print("abstract ");
    }

    private void printImports(PrintWriter out, ClassNode classNode) {
        List<String> imports = new ArrayList<String>();

        ModuleNode moduleNode = classNode.getModule();
        for (ImportNode importNode : moduleNode.getStarImports()) {
            imports.add(importNode.getPackageName());
        }

        for (ImportNode imp : moduleNode.getImports()) {
            if (imp.getAlias() == null)
                imports.add(imp.getType().getName());
        }

        imports.addAll(Arrays.asList(ResolveVisitor.DEFAULT_IMPORTS));

        for (String imp : imports) {
            String s = new StringBuilder()
                    .append("import ")
                    .append(imp)
                    .append((imp.charAt(imp.length() - 1) == '.') ? "*;" : ";")
                    .toString()
                    .replace('$', '.');
            out.println(s);
        }
        out.println();
    }

    public void clean() {
        for (String path : toCompile) {
            new File(outputPath, path + ".java").delete();
        }
    }

    private String escapeStringAnnotationValue(String value) {
        return value.replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"");
    }
}