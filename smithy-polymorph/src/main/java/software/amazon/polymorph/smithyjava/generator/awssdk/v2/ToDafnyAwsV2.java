package software.amazon.polymorph.smithyjava.generator.awssdk.v2;

import com.google.common.base.CaseFormat;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;

import dafny.DafnySequence;
import software.amazon.polymorph.smithyjava.MethodReference;
import software.amazon.polymorph.smithyjava.generator.ToDafny;
import software.amazon.polymorph.smithyjava.nameresolver.AwsSdkNativeV2;
import software.amazon.polymorph.smithyjava.nameresolver.Dafny;
import software.amazon.polymorph.utils.ModelUtils;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EnumDefinition;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.ErrorTrait;

import static software.amazon.smithy.utils.StringUtils.capitalize;
import static software.amazon.smithy.utils.StringUtils.uncapitalize;

/**
 * ToDafnyAwsV2 generates ToDafny.
 * ToDafny is a helper class for the AwsSdk's {@link ShimV2}.<p>
 * It holds methods to convert
 * a subset of an AWS SDK Service's types to Dafny types.<p>
 * The subset is composed of:
 * <ul>
 *   <li>All the Service's Operations' outputs
 *   <li>All the Service's Errors
 *   <li>All the fields contained by the above
 * </ul>
 * As such,
 * ToDafnyAwsV2 holds the logic to generate these methods based on:
 * <ul>
 *     <li>a smithy model</li>
 *     <li>knowledge of how smithy-dafny generates Dafny for AWS SDK</li>
 *     <li>knowledge of how Dafny compiles Java</li>
 *     <li>knowledge of the patterns of the AWS SDK V2 for Java</li>
 * </ul>
 */
public class ToDafnyAwsV2 extends ToDafny {
    protected static final ClassName JAVA_UTIL_COLLECTORS = ClassName.get("java.util.stream", "Collectors");
    protected static final ClassName JAVA_NIO_BYTEBUFFER = ClassName.get("java.nio", "ByteBuffer");


    // Hack to override subject to JavaAwsSdkV2
    // See code comment on ../library/ModelCodegen for details.
    final JavaAwsSdkV2 subject;

//    protected static Map<ShapeType, MethodReference> V2_CONVERSION_METHOD_FROM_SHAPE_TYPE;
//
//    static {
//        V2_CONVERSION_METHOD_FROM_SHAPE_TYPE = Map.ofEntries(
//            Map.entry(ShapeType.BLOB,
//                new MethodReference(JAVA_NIO_BYTEBUFFER, "wrap"))
//        );
//    }

    public ToDafnyAwsV2(JavaAwsSdkV2 awsSdk) {
        super(
                awsSdk,
                //TODO: JavaAwsSdkV2 should really have a declared packageName, not rely on the name resolver
                ClassName.get(awsSdk.dafnyNameResolver.packageName(), TO_DAFNY));

        this.subject = awsSdk;
    }

    @Override
    public Set<JavaFile> javaFiles() {
        JavaFile.Builder builder = JavaFile
                .builder(subject.dafnyNameResolver.packageName(), toDafny());
        return Collections.singleton(builder.build());
    }

//    @Override
//    protected MethodReference memberConversionMethodReference(MemberShape memberShape) {
//        Shape targetShape = subject.model.expectShape(memberShape.getTarget());
//        if (V2_CONVERSION_METHOD_FROM_SHAPE_TYPE.containsKey(targetShape.getType())) {
//            return V2_CONVERSION_METHOD_FROM_SHAPE_TYPE.get(targetShape.getType());
//        }
//        return super.memberConversionMethodReference(memberShape);
//    }

    TypeSpec toDafny() {
        LinkedHashSet<ShapeId> operationOutputs = subject.serviceShape
                .getOperations().stream()
                .map(shapeId -> subject.model.expectShape(shapeId, OperationShape.class))
                .map(OperationShape::getOutput).filter(Optional::isPresent).map(Optional::get)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<ShapeId> allRelevantShapeIds = ModelUtils.findAllDependentShapes(operationOutputs, subject.model);

        // In the AWS SDK for Java V2, Operation Outputs are special
        allRelevantShapeIds.removeAll(operationOutputs);
        // Enums are also a special case
        LinkedHashSet<ShapeId> enumShapeIds = new LinkedHashSet<>();
        allRelevantShapeIds.forEach(shapeId -> {
            Shape shape = subject.model.expectShape(shapeId);
            if (shape.hasTrait(EnumTrait.class)) {
                enumShapeIds.add(shapeId);
            }
        });
        allRelevantShapeIds.removeAll(enumShapeIds);

        final List<MethodSpec> convertOutputs = operationOutputs.stream()
                .map(this::generateConvertResponseV2).toList();
        final List<MethodSpec> convertAllRelevant = allRelevantShapeIds.stream()
                .map(this::generateConvert).filter(Objects::nonNull).toList();
        final List<MethodSpec> convertServiceErrors = ModelUtils.streamServiceErrors(subject.model, subject.serviceShape)
                .map(this::modeledError).collect(Collectors.toList());
        convertServiceErrors.add(generateConvertOpaqueError());
        // For enums, we generate overloaded methods,
        // one to convert instances of the Enum
        final List<MethodSpec> convertEnumEnum = enumShapeIds
                .stream().map(this::generateConvertEnumEnum).toList();
        // The other to convert String representatives of the enum
        final List<MethodSpec> convertEnumString = enumShapeIds
                .stream().map(this::generateConvertEnumString).toList();

        return TypeSpec
                .classBuilder(
                        ClassName.get(subject.dafnyNameResolver.packageName(), "ToDafny"))
                .addModifiers(Modifier.PUBLIC)
                .addMethods(convertOutputs)
                .addMethods(convertAllRelevant)
                .addMethods(convertServiceErrors)
                .addMethods(convertEnumEnum)
                .addMethods(convertEnumString)
                .build();
    }

    /** This method:
     * 1. Determines the Shape Type
     * 2. invokes the correct generate for that shape type
     */
    @SuppressWarnings({"OptionalGetWithoutIsPresent", "DuplicatedCode"})
    MethodSpec generateConvert(final ShapeId shapeId) {
        final Shape shape = subject.model.getShape(shapeId)
                .orElseThrow(() -> new IllegalStateException("Cannot find shape " + shapeId));
        return switch (shape.getType()) {
            // For the AWS SDK for Java, we do not generate converters for simple shapes
            case BLOB, BOOLEAN, STRING, TIMESTAMP, BYTE, SHORT,
                    INTEGER, LONG, BIG_DECIMAL, BIG_INTEGER, MEMBER -> null;
            case LIST -> modeledList(shape.asListShape().get());
            case MAP -> modeledMap(shape.asMapShape().get());
            case SET -> modeledSet(shape.asSetShape().get());
            case STRUCTURE -> generateConvertStructure(shapeId);
            default -> throw new UnsupportedOperationException(
                    "ShapeId %s is of Type %s, which is not yet supported for ToDafny"
                            .formatted(shapeId, shape.getType()));
        };
    }

    MethodSpec generateConvertEnumString(ShapeId shapeId) {
        final StringShape shape = subject.model.expectShape(shapeId, StringShape.class);
        String methodName = capitalize(shapeId.getName());
        TypeName dafnyEnumClass = subject.dafnyNameResolver.typeForShape(shapeId);

        MethodSpec.Builder builder = MethodSpec
                .methodBuilder(methodName)
                .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                .returns(dafnyEnumClass)
                .addParameter(subject.nativeNameResolver.classForString(), "nativeValue");
        builder.addStatement(
                "return $L($T.fromValue(nativeValue))",
                methodName,
                subject.nativeNameResolver.classForEnum(shape)
        );
        return builder.build();
    }

    protected MethodSpec generateConvertEnumEnum(ShapeId shapeId) {
        final StringShape shape = subject.model.expectShape(shapeId, StringShape.class);
        return modeledOVERIDEEnum(shape);
    }

    // TODO this is unshippable
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    protected MethodSpec modeledOVERIDEEnum(StringShape shape) {
        final ShapeId shapeId = shape.getId();
        String methodName = capitalize(shapeId.getName());
        final EnumTrait enumTrait = shape.getTrait(EnumTrait.class).orElseThrow(
            () -> new IllegalArgumentException(
                "Shape must have the enum trait. ShapeId: %s".formatted(shapeId))
        );
        if (!enumTrait.hasNames()) {
            throw new UnsupportedOperationException(
                "Unnamed enums not supported. ShapeId: %s".formatted(shapeId));
        }
        TypeName dafnyEnumClass = subject.dafnyNameResolver.typeForShape(shapeId);

        MethodSpec.Builder builder = MethodSpec
            .methodBuilder(methodName)
            .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
            .returns(dafnyEnumClass)
            .addParameter(subject.nativeNameResolver.classForEnum(shape), VAR_INPUT)
            .beginControlFlow("switch ($L)", VAR_INPUT);

        enumTrait.getValues().stream()
            .map(EnumDefinition::getName)
            .map(Optional::get)
            .peek(name -> {
                if (!ModelUtils.isValidEnumDefinitionName(name)) {
                    throw new UnsupportedOperationException(
                        "Invalid enum definition name: %s".formatted(name));
                }
            })
            .forEach(name -> builder
                .beginControlFlow("case $L:", transformEnumCaseName(dafnyEnumClass, name))
                .addStatement(
                    "return $T.$L()", dafnyEnumClass, Dafny.datatypeConstructorCreate(name))
                .endControlFlow()
            );

        builder.beginControlFlow("default:")
            .addStatement(
                "throw new $T($S + $L + $S)",
                RuntimeException.class,
                "Cannot convert ",
                VAR_INPUT,
                " to %s.".formatted(dafnyEnumClass))
            .endControlFlow();
        builder.endControlFlow();
        return builder.build();
    }

    protected final String transformEnumCaseName(final TypeName dafnyEnumClass, final String name) {
        if (name.contains("ECC_SECG_P256K1")) {
            return "ECC_SECG_P256_K1";
        }

        return enumRequiresUpperCamelcaseConversion(dafnyEnumClass.toString()) ? CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, name) : name;
    }

    // TODO unshippable
    protected final boolean enumRequiresUpperCamelcaseConversion(final String enumClassName) {
        Set<String> set = new HashSet<String>();

        set.add("Dafny.Com.Amazonaws.Kms.Types.KeyState");
        set.add("Dafny.Com.Amazonaws.Kms.Types.GrantOperation");

        return set.contains(enumClassName);
    }

    /**
     * Should be called for all of a service's operations' outputs.
     */
    MethodSpec generateConvertResponseV2(final ShapeId shapeId) {
        MethodSpec structure = generateConvertStructure(shapeId);
        MethodSpec.Builder builder = structure.toBuilder();
        builder.parameters.clear();
        builder.addParameter(subject.nativeNameResolver.typeForOperationOutput(shapeId), "nativeValue");
        return builder.build();
    }

    MethodSpec generateConvertStructure(final ShapeId shapeId) {
        final StructureShape structureShape = subject.model.expectShape(shapeId, StructureShape.class);
        return super.modeledStructure(structureShape);
    }

    /** For AWS SDK structure members, the getter is `get + capitalized member name`. */
    @Override
    protected CodeBlock getMember(CodeBlock variableName, MemberShape memberShape) {
        // TODO This is unshippable
        if ("AWSAccountId".equals(memberShape.getMemberName())) {
            return CodeBlock.of("$L.awsAccountId()", variableName);
        }
        // TODO This is unshippable
        if ("message".equals(uncapitalize(memberShape.getMemberName()))) {
            return CodeBlock.of("$L.get$L()", variableName, capitalize(memberShape.getMemberName()));
        }
        return CodeBlock.of("$L.$L()", variableName, uncapitalize(memberShape.getMemberName()));
    }

    /**
     * We have to customize
     * List conversion for the AWS SDK for Java V2 because
     * AWS SDK Java V2 treats Enums in a special way.
     * See the comment on
     * {@link software.amazon.polymorph.smithyjava.nameresolver.AwsSdkNativeV2#typeForShapeNoEnum}
     **/
    @Override
    protected MethodSpec modeledList(ListShape shape) {
        MemberShape memberShape = shape.getMember();
        CodeBlock memberConverter = memberConversionMethodReference(memberShape).asFunctionalReference();
        CodeBlock genericCall = AGGREGATE_CONVERSION_METHOD_FROM_SHAPE_TYPE.get(shape.getType()).asNormalReference();
        CodeBlock getTypeDescriptor = subject.dafnyNameResolver.typeDescriptor(memberShape.getTarget());

        String nativeValueVar = "nativeValue";
//        if (getTypeDescriptor.toString().contains("EncryptionAlgorithmSpec")) {
//            nativeValueVar = nativeValueVar + ".stream().map(Object::toString).collect(Collectors.toList())";
//        }

        ParameterSpec parameterSpec = ParameterSpec
                .builder(subject.nativeNameResolver.typeForListSetOrMapNoEnum(shape.getId()), "nativeValue")
                .build();
        return MethodSpec
                .methodBuilder(capitalize(shape.getId().getName()))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(subject.dafnyNameResolver.typeForAggregateWithWildcard(shape.getId()))
                .addParameter(parameterSpec)
                .addStatement("return $L(\n$L, \n$L, \n$L)",
                        genericCall, nativeValueVar, memberConverter, getTypeDescriptor)
                .build();
    }

    /**
     * We have to customize
     * Set conversion for the AWS SDK for Java V2 because
     * AWS SDK Java V2 treats Enums in a special way.
     * See the comment on
     * {@link software.amazon.polymorph.smithyjava.nameresolver.AwsSdkNativeV2#typeForShapeNoEnum}
     **/
    @Override
    protected MethodSpec modeledSet(SetShape shape) {
        MemberShape memberShape = shape.getMember();
        CodeBlock memberConverter = memberConversionMethodReference(memberShape).asFunctionalReference();
        CodeBlock genericCall = AGGREGATE_CONVERSION_METHOD_FROM_SHAPE_TYPE.get(shape.getType()).asNormalReference();
        ParameterSpec parameterSpec = ParameterSpec
                .builder(subject.nativeNameResolver.typeForListSetOrMapNoEnum(shape.getId()), "nativeValue")
                .build();
        return MethodSpec
                .methodBuilder(capitalize(shape.getId().getName()))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(subject.dafnyNameResolver.typeForAggregateWithWildcard(shape.getId()))
                .addParameter(parameterSpec)
                .addStatement("return $L(\nnativeValue, \n$L)",
                        genericCall, memberConverter)
                .build();
    }

    /**
     * We have to customize
     * Map conversion for the AWS SDK for Java V2 because
     * AWS SDK Java V2 treats Enums in a special way.
     * See the comment on
     * {@link software.amazon.polymorph.smithyjava.nameresolver.AwsSdkNativeV2#typeForShapeNoEnum}
     **/
    @Override
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    protected MethodSpec modeledMap(MapShape shape) {
        MemberShape keyShape = shape.getKey().asMemberShape().get();
        CodeBlock keyConverter = memberConversionMethodReference(keyShape).asFunctionalReference();
        MemberShape valueShape = shape.getValue().asMemberShape().get();
        CodeBlock valueConverter = memberConversionMethodReference(valueShape).asFunctionalReference();
        CodeBlock genericCall = AGGREGATE_CONVERSION_METHOD_FROM_SHAPE_TYPE.get(shape.getType()).asNormalReference();
        ParameterSpec parameterSpec = ParameterSpec
                .builder(subject.nativeNameResolver.typeForListSetOrMapNoEnum(shape.getId()), "nativeValue")
                .build();
        return MethodSpec
                .methodBuilder(capitalize(shape.getId().getName()))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(subject.dafnyNameResolver.typeForAggregateWithWildcard(shape.getId()))
                .addParameter(parameterSpec)
                .addStatement("return $L(\nnativeValue, \n$L, \n$L)",
                        genericCall, keyConverter, valueConverter)
                .build();
    }

    MethodSpec generateConvertOpaqueError() {
        // Opaque Errors are not in the model,
        // so we cannot use any of our helper methods for this method.

        // This is memberDeclaration from above,
        // but with calls to target.dafnyNameResolver replaced with their expected response.
        CodeBlock memberDeclaration = CodeBlock.of(
                "$T $L",
                ParameterizedTypeName.get(
                        ClassName.get("Wrappers_Compile", "Option"),
                        ParameterizedTypeName.get(
                                ClassName.get(DafnySequence.class),
                                WildcardTypeName.subtypeOf(Character.class))
                ),
                "message"
        );
        // This is memberAssignment from above,
        // but with calls to dafnyNameResolver replaced with their expected response.
        CodeBlock memberAssignment = CodeBlock.of(
                "$L = $T.nonNull($L) ?\n$T.create_Some($T.$L($L))\n: $T.create_None()",
                "message",
                ClassName.get(Objects.class),
                "nativeValue.getMessage()",
                ClassName.get("Wrappers_Compile", "Option"),
                COMMON_TO_DAFNY_SIMPLE,
                SIMPLE_CONVERSION_METHOD_FROM_SHAPE_TYPE.get(ShapeType.STRING).methodName(),
                "nativeValue.getMessage()",
                ClassName.get("Wrappers_Compile", "Option")
        );
        return MethodSpec.methodBuilder("Error")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(subject.dafnyNameResolver.abstractClassForError())
                .addParameter(subject.nativeNameResolver.baseErrorForService(), "nativeValue")
                .addStatement(memberDeclaration)
                .addStatement(memberAssignment)
                .addStatement("return new $T(message)", subject.dafnyNameResolver.classForOpaqueError())
                .build();
    }
}
