package com.flipboard.goldengate;

import android.webkit.WebView;

import com.google.gson.reflect.TypeToken;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public class BridgeInterface {

    public final String name;
    public final boolean isDebug;
    private TypeMirror type;
    private ArrayList<BridgeMethod> bridgeMethods = new ArrayList<>();
    private ArrayList<BridgeProperty> bridgeProperties = new ArrayList<>();
    private boolean needsCallbacks = false;

    public BridgeInterface(Element element, Elements elementUtils, Types typeUtils) {
        this.name = element.getSimpleName().toString();
        TypeElement typeElement = elementUtils.getTypeElement(element.asType().toString());
        this.isDebug = typeElement != null && typeElement.getAnnotation(Debug.class) != null;
        this.type = element.asType();

        for (Element method : element.getEnclosedElements()) {
            if (method.getKind() == ElementKind.METHOD) {
                ExecutableElement executableElement = (ExecutableElement) method;
                TypeElement methodTypeElement = elementUtils.getTypeElement(executableElement.asType().toString());
                if (methodTypeElement != null && methodTypeElement.getAnnotation(Property.class) != null) {
                    bridgeProperties.add(new BridgeProperty(executableElement));
                } else {
                    bridgeMethods.add(new BridgeMethod(executableElement, elementUtils, typeUtils));
                }
            }
        }

        // 检查是否需要回调
        for (BridgeMethod method : bridgeMethods) {
            if (method.hasCallbackParameters || method.callback != null) {
                needsCallbacks = true;
                break;
            }
        }
        if (!needsCallbacks) {
            for (BridgeProperty property : bridgeProperties) {
                if (property.callback != null) {
                    needsCallbacks = true;
                    break;
                }
            }
        }
    }

    public void writeToFiler(Filer filer) throws IOException {
        String packageName = getPackageName(type);

        // Build Bridge class
        TypeSpec.Builder bridge = TypeSpec.classBuilder(name + "Bridge")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(TypeName.get(type))
                .superclass(JavaScriptBridge.class);

        // Generate the result bridge if necessary
        if (needsCallbacks) {
            bridge.addField(ClassName.get(packageName, name + "Bridge", "ResultBridge"), "resultBridge", Modifier.PRIVATE, Modifier.FINAL);
            bridge.addField(AtomicLong.class, "receiverIds", Modifier.PRIVATE, Modifier.FINAL);
            Type callbacksMapType = new TypeToken<Map<Long, WeakReference<Callback<String>>>>(){}.getType();
            Type callbackType = new TypeToken<Callback<String>>(){}.getType();
            bridge.addType(TypeSpec.classBuilder("ResultBridge")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .addField(FieldSpec.builder(callbacksMapType, "callbacks")
                            .initializer("new $T<>()", HashMap.class)
                            .build())
                    .addMethod(MethodSpec.methodBuilder("registerCallback")
                            .addParameter(long.class, "receiver")
                            .addParameter(callbackType, "cb")
                            .addCode(CodeBlock.builder()
                                    .addStatement("callbacks.put($N, new $T($N))", "receiver", WeakReference.class, "cb").build())
                            .build())
                    .addMethod(MethodSpec.methodBuilder("onResult")
                            .addModifiers(Modifier.PUBLIC)
                            .addAnnotation(ClassName.get("android.webkit", "JavascriptInterface"))
                            .addParameter(String.class, "result")
                            .addCode(CodeBlock.builder()
                                    .beginControlFlow("try")
                                    .addStatement("$T $N = new $T($N)", ClassName.get("org.json", "JSONObject"), "json", ClassName.get("org.json", "JSONObject"), "result")
                                    .addStatement("$T $N = $N.getLong($S)", long.class, "receiver", "json", "receiver")
                                    .addStatement("$T $N = $N.get($S).toString()", String.class, "realResult", "json", "result")
                                    .addStatement("$T $N = $N.get($N).get()", callbackType, "callback", "callbacks", "receiver")
                                    .beginControlFlow("if ($N != null) ", "callback")
                                    .addStatement("$N.onResult($N)", "callback", "realResult")
                                    .endControlFlow()
                                    .nextControlFlow("catch (org.json.JSONException e)")
                                    .addStatement("$N.printStackTrace()", "e")
                                    .endControlFlow()
                                    .build())
                            .build())
                    .build());
        }

        // Add Bridge constructor using globally configured json serializer
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(WebView.class, "webView")
                .addStatement("super($N)", "webView");
        if (needsCallbacks) {
            constructorBuilder
                .addStatement("this.$N = new ResultBridge()", "resultBridge")
                .addStatement("this.$N = new $T()", "receiverIds", AtomicLong.class)
                .addStatement("this.$N.addJavascriptInterface($N, $L)", "webView", "resultBridge", "\"" + name + "\"")
                .addCode("evaluateJavascript(webView, \n" +
                        "                \"function GoldenGate$$$$CreateCallback(receiver) {\" +\n" +
                        "                \"    return function(result) {\" +\n" +
                        "                \"        $N.onResult(JSON.stringify({receiver: receiver, result: JSON.stringify(result)}))\" +\n" +
                        "                \"    }\" +\n" +
                        "                \"}\");", name);
        }
        bridge.addMethod(
                constructorBuilder.build()
        );

        // Add Bridge constructor using custom json serializer
        constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(WebView.class, "webView")
                .addParameter(JsonSerializer.class, "jsonSerializer")
                .addStatement("super($N, $N)", "webView", "jsonSerializer");
        if (needsCallbacks) {
            constructorBuilder
                .addStatement("this.$N = new ResultBridge()", "resultBridge")
                .addStatement("this.$N = new $T()", "receiverIds", AtomicLong.class)
                .addStatement("this.$N.addJavascriptInterface($N, $L)", "webView", "resultBridge", "\"" + name + "\"")
                .addCode("evaluateJavascript(webView, \n" +
                        "                \"function GoldenGate$$$$CreateCallback(receiver) {\" +\n" +
                        "                \"    return function(result) {\" +\n" +
                        "                \"        $N.onResult(JSON.stringify({receiver: receiver, result: JSON.stringify(result)}))\" +\n" +
                        "                \"    }\" +\n" +
                        "                \"}\");", name);
        }
        bridge.addMethod(
            constructorBuilder.build()
        );

        // Add Bridge methods
        for (BridgeMethod method : bridgeMethods) {
            bridge.addMethod(method.toMethodSpec(this));
        }

        // Add Bridge property methods
        for (BridgeProperty property : bridgeProperties) {
            bridge.addMethod(property.toMethodSpec(this));
        }

        // Write source
        JavaFile javaFile = JavaFile.builder(packageName, bridge.build()).build();
        javaFile.writeTo(filer);
    }

    private String getPackageName(TypeMirror type) {
        Element element = Processor.instance.typeUtils.asElement(type);
        while (!(element instanceof PackageElement)) {
            element = element.getEnclosingElement();
        }
        return ((PackageElement) element).getQualifiedName().toString();
    }

    public boolean needsCallbacks() {
        return needsCallbacks;
    }
}
