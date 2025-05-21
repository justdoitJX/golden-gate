package com.flipboard.goldengate;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public class BridgeMethod {

    public final String javaName;
    public final String name;
    public final List<BridgeParameter> parameters = new ArrayList<>();
    public BridgeCallback callback;
    public boolean hasCallbackParameters = false;

    public BridgeMethod(ExecutableElement e, Elements elementUtils, Types typeUtils) {
        this.javaName = e.getSimpleName().toString();
        this.name = e.getAnnotation(Method.class) != null ? e.getAnnotation(Method.class).value() : javaName;

        TypeMirror callbackMirror = Util.typeFromClass(typeUtils, elementUtils, Callback.class);

        for (int i = 0; i < e.getParameters().size(); i++) {
            VariableElement param = e.getParameters().get(i);
            boolean isJavascriptCallback = param.getAnnotation(JavascriptCallback.class) != null;

            if (i == e.getParameters().size() - 1 && Util.isCallback(param) && !isJavascriptCallback) {
                callback = new BridgeCallback(param);
            } else {
                if (isJavascriptCallback) {
                    if (Util.isCallback(param)) {
                        parameters.add(new BridgeCallback(param));
                        hasCallbackParameters = true;
                    } else {
                        throw new IllegalArgumentException("Param with annotation @JavascriptCallback must be of type com.flipboard.goldengate.Callback");
                    }
                } else {
                    if (typeUtils.isSameType(param.asType(), callbackMirror)) {
                        hasCallbackParameters = true;
                    }
                    parameters.add(new BridgeParameter(param));
                }
            }
        }
    }

    public MethodSpec toMethodSpec(BridgeInterface bridge) {
        MethodSpec.Builder methodSpec = MethodSpec.methodBuilder(javaName).addModifiers(Modifier.PUBLIC);
        for (int i = 0; i < parameters.size(); i++) {
            methodSpec.addParameter(TypeName.get(parameters.get(i).type), parameters.get(i).name, Modifier.FINAL);
        }

        if (callback != null && bridge.needsCallbacks()) {
            methodSpec.addParameter(TypeName.get(callback.type), callback.name, Modifier.FINAL);
            methodSpec.addCode(CodeBlock.builder()
                    .addStatement("$T id = receiverIds.incrementAndGet()", long.class)
                    .add("this.resultBridge.registerCallback(id, new Callback<$T>() {\n", String.class)
                    .indent()
                    .add("@$T\n", Override.class)
                    .add("public void onResult(String result) {\n")
                    .indent()
                    .addStatement("$N.onResult(fromJson(result, $T.class))", callback.name, callback.genericType)
                    .unindent()
                    .add("}\n")
                    .unindent()
                    .addStatement("})")
                    .build());
        }

        if (hasCallbackParameters && bridge.needsCallbacks()) {
            methodSpec.addStatement("$T<$T, $T> idMap = new $T<>()", Map.class, String.class, Long.class, HashMap.class);
        }
        for (BridgeParameter parameter : parameters) {
            if (parameter instanceof BridgeCallback && bridge.needsCallbacks()) {
                BridgeCallback callbackParameter = (BridgeCallback) parameter;
                methodSpec.addCode(CodeBlock.builder()
                        .addStatement("idMap.put($S, receiverIds.incrementAndGet())", callbackParameter.name)
                        .add("this.resultBridge.registerCallback(idMap.get($S), new Callback<$T>() {\n", callbackParameter.name, String.class)
                        .indent()
                        .add("@$T\n", Override.class)
                        .add("public void onResult(String result) {\n")
                        .indent()
                        .addStatement("$N.onResult(fromJson(result, $T.class))", callbackParameter.name, callbackParameter.genericType)
                        .unindent()
                        .add("}\n")
                        .unindent()
                        .addStatement("})")
                        .build());
            }
        }

        // 生成 JavaScript 调用代码
        CodeBlock.Builder codeBlock = CodeBlock.builder();
        codeBlock.addStatement("$T javascript = \"$L(\"", String.class, name);
        boolean first = true;
        for (BridgeParameter parameter : parameters) {
            if (!first) {
                codeBlock.add(", ");
            }
            if (parameter instanceof BridgeCallback && bridge.needsCallbacks()) {
                codeBlock.add("GoldenGate$$$$CreateCallback(idMap.get($S))", parameter.name);
            } else {
                codeBlock.add("toJson($N)", parameter.name);
            }
            first = false;
        }
        if (callback != null && bridge.needsCallbacks()) {
            if (!first) {
                codeBlock.add(", ");
            }
            codeBlock.add("GoldenGate$$$$CreateCallback(id)");
        }
        codeBlock.add(");\"");
        if (bridge.isDebug) {
            codeBlock.addStatement("android.util.Log.d($S, javascript)", bridge.name);
        }
        codeBlock.addStatement("evaluateJavascript(webView, javascript)");
        methodSpec.addCode(codeBlock.build());

        return methodSpec.build();
    }

}
