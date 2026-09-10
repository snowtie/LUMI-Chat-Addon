package com.snowtie.lumichataddon;

import com.group_finity.mascot.lumi.plugin.LumiTransformer;

import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;

final class LumiChatTransformer implements LumiTransformer {
    private static final String SETTINGS_DIALOG = "com/group_finity/mascot/lumi/ai/AiSettingsDialog";
    private static final String TTS_CLIENT = "com/group_finity/mascot/lumi/ai/TtsClient";
    private static final ClassDesc ADDON = ClassDesc.of("com.snowtie.lumichataddon.LumiChatAddonPlugin");
    private static final ClassDesc PCM_AUDIO = ClassDesc.of("com.group_finity.mascot.lumi.plugin.PcmAudio");
    private static final ClassDesc HOOKS = ClassDesc.of("com.group_finity.mascot.lumi.plugin.PluginHooks");
    private static final ClassDesc OBJECT_ARRAY = CD_Object.arrayType();
    private static final MethodTypeDesc DECIDE =
            MethodTypeDesc.of(CD_Object, CD_String, CD_Object, OBJECT_ARRAY);

    private final String dialogInitHook;
    private final String dialogLoadHook;
    private final String dialogApplyHook;

    LumiChatTransformer(
            String dialogInitHook,
            String dialogLoadHook,
            String dialogApplyHook) {
        this.dialogInitHook = dialogInitHook;
        this.dialogLoadHook = dialogLoadHook;
        this.dialogApplyHook = dialogApplyHook;
    }

    @Override
    public boolean wants(String className) {
        String normalized = className.replace('.', '/');
        return SETTINGS_DIALOG.equals(normalized) || TTS_CLIENT.equals(normalized);
    }

    @Override
    public byte[] transform(String className, byte[] bytes) {
        if (!wants(className)) {
            return bytes;
        }
        String normalized = className.replace('.', '/');
        if (TTS_CLIENT.equals(normalized)
                && new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("gptSovitsSelected")) {
            return bytes;
        }
        ClassFile classFile = ClassFile.of();
        return classFile.transformClass(
                classFile.parse(bytes),
                (builder, element) -> transformElement(builder, element, normalized));
    }

    ClassFileTransformer asClassFileTransformer() {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {
                return wants(className) ? LumiChatTransformer.this.transform(className, classfileBuffer) : null;
            }
        };
    }

    private void transformElement(
            java.lang.classfile.ClassBuilder builder, ClassElement element, String className) {
        if (!(element instanceof MethodModel method)) {
            builder.with(element);
            return;
        }
        String name = method.methodName().stringValue();
        String descriptor = method.methodType().stringValue();
        CodeTransform transform = null;
        if (TTS_CLIENT.equals(className) && name.equals("configured") && descriptor.equals("()Z")) {
            transform = ttsEntry(true);
        } else if (TTS_CLIENT.equals(className) && name.equals("synthesize")
                && descriptor.equals("(Ljava/lang/String;Ljava/lang/String;)Lcom/group_finity/mascot/lumi/plugin/PcmAudio;")) {
            transform = ttsEntry(false);
        } else if (SETTINGS_DIALOG.equals(className) && name.equals("<init>")) {
            transform = beforeReturn(dialogInitHook);
        } else if (SETTINGS_DIALOG.equals(className) && name.equals("apply") && descriptor.equals("()Z")) {
            transform = beforeSuccessfulReturn(dialogApplyHook);
        } else if (SETTINGS_DIALOG.equals(className) && descriptor.equals("()V")) {
            String hook = switch (name) {
                case "load" -> dialogLoadHook;
                case "apply" -> dialogApplyHook;
                default -> null;
            };
            if (hook != null) {
                transform = beforeReturn(hook);
            }
        }
        if (transform == null || method.code().isEmpty()) {
            builder.with(element);
            return;
        }
        builder.transformMethod(method, MethodTransform.transformingCode(transform));
    }

    private CodeTransform beforeReturn(String hook) {
        return CodeTransform.ofStateful(() -> (builder, element) -> {
            if (element instanceof ReturnInstruction instruction && instruction.opcode() == Opcode.RETURN) {
                emitDialogHook(builder, hook);
            }
            builder.with(element);
        });
    }

    private static CodeTransform ttsEntry(boolean configured) {
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder builder) {
                builder.invokestatic(ADDON, "gptSovitsSelected", MethodTypeDesc.ofDescriptor("()Z"))
                        .ifThen(branch -> {
                            if (configured) {
                                branch.invokestatic(ADDON, "gptSovitsConfigured", MethodTypeDesc.ofDescriptor("()Z"))
                                        .ireturn();
                            } else {
                                branch.aload(0).aload(1)
                                        .invokestatic(ADDON, "synthesizeGptSovits",
                                                MethodTypeDesc.of(PCM_AUDIO, CD_String, CD_String))
                                        .areturn();
                            }
                        });
            }

            @Override
            public void accept(CodeBuilder builder, CodeElement element) {
                builder.with(element);
            }
        };
    }

    private static CodeTransform beforeSuccessfulReturn(String hook) {
        return CodeTransform.ofStateful(() -> (builder, element) -> {
            if (element instanceof ReturnInstruction instruction && instruction.opcode() == Opcode.IRETURN) {
                // 새 LUMI Chat의 검증 실패(false)는 보존하고 저장 성공 때만 애드온 설정을 반영합니다.
                builder.dup().ifThen(branch -> emitDialogHook(branch, hook));
            }
            builder.with(element);
        });
    }

    private static void emitDialogHook(CodeBuilder builder, String hook) {
        builder.ldc(hook)
                .aload(0)
                .iconst_2()
                .anewarray(CD_Object)
                .dup()
                .iconst_0()
                .ldc("dialog")
                .aastore()
                .dup()
                .iconst_1()
                .aload(0)
                .aastore()
                .invokestatic(HOOKS, "decide", DECIDE)
                .pop();
    }
}
