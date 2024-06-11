// Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.polymorph.smithygo;

import software.amazon.polymorph.smithygo.codegen.GenerationContext;
import software.amazon.polymorph.smithygo.codegen.GoDelegator;
import software.amazon.polymorph.smithygo.codegen.GoWriter;
import software.amazon.polymorph.smithygo.codegen.SmithyGoDependency;
import software.amazon.polymorph.smithygo.codegen.StructureGenerator;
import software.amazon.polymorph.smithygo.codegen.UnionGenerator;
import software.amazon.polymorph.smithygo.nameresolver.DafnyNameResolver;
import software.amazon.polymorph.smithygo.nameresolver.SmithyNameResolver;
import software.amazon.polymorph.traits.ExtendableTrait;
import software.amazon.polymorph.traits.LocalServiceTrait;
import software.amazon.polymorph.traits.ReferenceTrait;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.UnitTypeTrait;

import java.util.Collection;
import java.util.stream.Collectors;


public class LocalServiceGenerator implements Runnable {
    private final GenerationContext context;
    private final ServiceShape service;
    private final TopDownIndex topDownIndex;
    private final GoDelegator writerDelegator;
    private final Model model;
    private final SymbolProvider symbolProvider;

    public LocalServiceGenerator(GenerationContext context, ServiceShape service) {
        this.context = context;
        this.service = service;
        model = context.model();
        topDownIndex = TopDownIndex.of(model);
        writerDelegator = context.writerDelegator();
        symbolProvider = context.symbolProvider();
    }

    @Override
    public void run() {
        writerDelegator.useShapeWriter(service, this::generateService);
    }

    private void generateService(GoWriter writer) {
        if (service.hasTrait(LocalServiceTrait.class)) {
            generateClient(writer);
            generateUnmodelledErrors(context);
            generateReferencedResources(context);
            generateUnboundedStructures(context);
        }
        generateShim();

    }
    void generateClient(GoWriter writer) {
        // Generate each operation for the service. We do this here instead of via the operation visitor method to
        // limit it to the operations bound to the service.
        final var serviceSymbol = symbolProvider.toSymbol(service);
        final var serviceTrait = service.expectTrait(LocalServiceTrait.class);
        final var configSymbol = symbolProvider.toSymbol(model.expectShape(serviceTrait.getConfigId()));

        writerDelegator.useFileWriter("%s/types.go".formatted(SmithyNameResolver.smithyTypesNamespace(service)), SmithyNameResolver.smithyTypesNamespace(service), writer1 -> {
            new StructureGenerator(model, symbolProvider, writer1,
                                   model.expectShape(serviceTrait.getConfigId()).asStructureShape().get()).run();
            model.getUnionShapes().stream()
            .filter(unionShape -> unionShape.getId().getNamespace().equals(service.getId().getNamespace()))
            .forEach(unionShape -> {
                new UnionGenerator(model, symbolProvider, unionShape).generateUnion(writer1);
            });
        });

        writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(context.settings().getService().getNamespace()), DafnyNameResolver.dafnyTypesNamespace(service));
        writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(context.settings().getService().getNamespace()), DafnyNameResolver.dafnyNamespace(service));
        writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(context.settings().getService().getNamespace()), SmithyNameResolver.smithyTypesNamespace(service));
        writer.addUseImports(SmithyGoDependency.CONTEXT);

        final var dafnyClient = DafnyNameResolver.getDafnyClient(service, serviceTrait.getSdkId());
        writer.write("""
                             type $T struct {
                                 dafnyClient *$L
                             }
                             
                             func NewClient(clientConfig $L) (*$T, error) {
                                 var dafnyConfig = $L(clientConfig)
                                 var dafny_response = $L(dafnyConfig)
                                 if (dafny_response.Is_Failure()) {
                                      panic("Client construction failed. This should never happen")
                                 }
                                 var dafnyClient = dafny_response.Extract().(*$L)
                                 client := &$T { dafnyClient }
                                 return client, nil
                             }
                             """,
                     serviceSymbol, dafnyClient, SmithyNameResolver.getSmithyType(service, configSymbol), serviceSymbol,
                     SmithyNameResolver.getToDafnyMethodName(service, context.model().expectShape(serviceTrait.getConfigId()), ""),
                     DafnyNameResolver.createDafnyClient(service, serviceTrait.getSdkId()),
                     dafnyClient, serviceSymbol);

        service.getOperations().forEach(operation -> {
            final var operationShape = model.expectShape(operation, OperationShape.class);
            final var inputShape = model.expectShape(operationShape.getInputShape());
            final var outputShape = model.expectShape(operationShape.getOutputShape());
            final var inputType = inputShape.hasTrait(UnitTypeTrait.class) ? ""
                                                                           : ", params %s.%s".formatted(SmithyNameResolver.smithyTypesNamespace(inputShape), inputShape.toShapeId().getName());
            final var outputType = outputShape.hasTrait(UnitTypeTrait.class) ? ""
                                                                             : "*%s.%s,".formatted(SmithyNameResolver.smithyTypesNamespace(outputShape), outputShape.toShapeId().getName());
            String validationCheck = "";
            if(!inputType.equals("")) {
                validationCheck = """
                    err := params.Validate()
                    if err != nil {
                """;
                if(outputType.equals("")) {
                    validationCheck += "return err }";
                }
                else{
                    validationCheck += "return nil, err }";
                }
            }
            String baseClientCall;
            if (inputShape.hasTrait(UnitTypeTrait.class)) {
                baseClientCall = "var dafny_response = client.dafnyClient.%s()".formatted(operationShape.getId().getName());
            } else {
                baseClientCall = """
                        var dafny_request %s = %s(params)
                        var dafny_response = client.dafnyClient.%s(dafny_request)
                        """.formatted(DafnyNameResolver.getDafnyType(inputShape, symbolProvider.toSymbol(inputShape)),
                                      SmithyNameResolver.getToDafnyMethodName(service, inputShape, ""), operationShape.getId().getName());
            }

            String returnResponse, returnError;
            if (outputShape.hasTrait(UnitTypeTrait.class)) {
                returnResponse = "return nil";
                returnError = "return";
            } else {
                returnResponse = """
                        var native_response = %s(dafny_response.Extract().(%s))
                        return &native_response, nil
                        """.formatted(SmithyNameResolver.getFromDafnyMethodName(service, outputShape, ""),
                                      DafnyNameResolver.getDafnyType(outputShape, symbolProvider.toSymbol(outputShape)));
                returnError = "return nil,";
            }

            writer.write("""
                                   func (client *$T) $L(ctx context.Context $L) ($L error) {
                                       $L
                                       $L
                                       if (dafny_response.Is_Failure()) {
                                           err := dafny_response.Dtor_error().($L.Error);
                                           // Service Errors
                                           ${C|}
                                           
                                           //DependentErrors
                                           if err.Is_CollectionOfErrors() {
                                               $L CollectionOfErrors_Output_FromDafny(err)
                                           }
                                           if err.Is_Opaque() {
                                               $L OpaqueError_Output_FromDafny(err)
                                           }
                                       }
                                       $L
                                   }
                                 """,
                         serviceSymbol,
                         operationShape.getId().getName(),
                         inputType, outputType,
                         validationCheck,
                         baseClientCall,
                         DafnyNameResolver.dafnyTypesNamespace(operationShape),
                         writer.consumer(w -> {
                             for (var error : service.getErrors()) {
                                 w.write("""
                                                                        if err.Is_$L() {
                                                                        $L $L(err)
                                                                    }
                                                 """, error.getName(), returnError, SmithyNameResolver.getFromDafnyMethodName(service, context.model().expectShape(error), ""));
                             }
                         }),
//                         writer.consumer(w -> {
//                        for (var errorShape : model.getShapesWithTrait(ErrorTrait.class).stream().filter(x -> !x.toShapeId().getNamespace().equals(service.toShapeId().getNamespace())).toList()) {
//                            w.write("""
//                                                                        if err.Is_$L() {
//                                                                        $L $L_Output_FromDafny(err)
//                                                                    }
//                                                 """, errorShape.toShapeId().getNamespace(), returnError, errorShape.toShapeId().getName());
//                        }
//                    }),
                         returnError, returnError, returnResponse
            );
        });
    }

    void generateShim() {
        final var namespace = "%swrapped".formatted(DafnyNameResolver.dafnyNamespace(service));

        writerDelegator.useFileWriter("%s/shim.go".formatted(namespace), namespace, writer -> {

            writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(context.settings().getService().getNamespace()), DafnyNameResolver.dafnyTypesNamespace(service));
            writer.addImportFromModule("github.com/dafny-lang/DafnyStandardLibGo", "Wrappers");
            writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(context.settings().getService().getNamespace()), SmithyNameResolver.smithyTypesNamespace(service));
            writer.addUseImports(SmithyGoDependency.CONTEXT);
            writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(context.settings().getService().getNamespace()), SmithyNameResolver.shapeNamespace(service));

            if (service.hasTrait(LocalServiceTrait.class)) {
                final var serviceTrait = service.expectTrait(LocalServiceTrait.class);
                final var configShape = model.expectShape(serviceTrait.getConfigId());
                final var configSymbol = symbolProvider.toSymbol(configShape);

                writer.write("""
                                     type Shim struct {
                                         $L
                                         client *$L.Client
                                     }
                                     """,
                             DafnyNameResolver.getDafnyInterfaceClient(service),
                             SmithyNameResolver.shapeNamespace(service)
                );

                writer.write("""
                                                       
                                     func Wrapped$L(inputConfig $L) Wrappers.Result {
                                         var nativeConfig = $L.$L(inputConfig)
                                         var nativeClient, nativeError = $L.NewClient(nativeConfig)
                                         if nativeError != nil {
                                            return Wrappers.Companion_Result_.Create_Failure_($L.Companion_Error_.Create_Opaque_(nativeError))
                                         }
                                         return Wrappers.Companion_Result_.Create_Success_(&Shim{client: nativeClient})
                                     }
                                     """,
                             serviceTrait.getSdkId(), DafnyNameResolver.getDafnyType(configShape, configSymbol),
                             SmithyNameResolver.shapeNamespace(model.expectShape(serviceTrait.getConfigId())), SmithyNameResolver.getFromDafnyMethodName(service, model.expectShape(serviceTrait.getConfigId()), ""),
                             SmithyNameResolver.shapeNamespace(service), DafnyNameResolver.dafnyTypesNamespace(service)
                );
            }


            service.getOperations().forEach(operation -> {
                final var operationShape = model.expectShape(operation, OperationShape.class);
                final var inputShape = model.expectShape(operationShape.getInputShape());
                final var outputShape = model.expectShape(operationShape.getOutputShape());
                final var inputType = inputShape.hasTrait(UnitTypeTrait.class) ? ""
                                                                               : "input %s".formatted(DafnyNameResolver.getDafnyType(inputShape, symbolProvider.toSymbol(inputShape)));

                final var typeConversion = inputShape.hasTrait(UnitTypeTrait.class) ? ""
                                                                                    : "var native_request = %s.%s(input)".formatted(SmithyNameResolver.shapeNamespace(operationShape), SmithyNameResolver.getFromDafnyMethodName(service, inputShape, ""));

                final var clientCall = "shim.client.%s(context.Background() %s)".formatted(operationShape.getId().getName(),
                                                                                           inputShape.hasTrait(UnitTypeTrait.class) ? "" : ", native_request");

                String clientResponse, returnResponse;
                if (outputShape.hasTrait(UnitTypeTrait.class)) {
                    clientResponse = "var native_error";
                    returnResponse = "dafny.TupleOf()";
                    writer.addImportFromModule("github.com/dafny-lang/DafnyRuntimeGo", "dafny");
                } else {
                    clientResponse = "var native_response, native_error";
                    returnResponse = "%s.%s(*native_response)".formatted(SmithyNameResolver.shapeNamespace(operationShape), SmithyNameResolver.getToDafnyMethodName(service, outputShape, ""));
                }

                writer.write("""
                                       func (shim *Shim) $L($L) Wrappers.Result {
                                           $L
                                           $L = $L
                                           if native_error != nil {
                                               switch native_error.(type) {
                                                ${C|}
                                                case $L.CollectionOfErrors:
                                                    return Wrappers.Companion_Result_.Create_Failure_($L.CollectionOfErrors_Input_ToDafny(native_error.($L.CollectionOfErrors)))
                                                case $L.OpaqueError:
                                                    return Wrappers.Companion_Result_.Create_Failure_($L.OpaqueError_Input_ToDafny(native_error.($L.OpaqueError)))
                                                default:
                                                    return Wrappers.Companion_Result_.Create_Failure_(native_error)
                                                }
                                           }
                                           return Wrappers.Companion_Result_.Create_Success_($L)
                                       }
                                     """,
                             operationShape.getId().getName(),
                             inputType, typeConversion, clientResponse, clientCall,
                             writer.consumer(this::shimErrors),
                             SmithyNameResolver.smithyTypesNamespace(service),
                             SmithyNameResolver.smithyTypesNamespace(service),
                             SmithyNameResolver.shapeNamespace(service),
                             SmithyNameResolver.smithyTypesNamespace(service),
                             SmithyNameResolver.shapeNamespace(service),
                             SmithyNameResolver.smithyTypesNamespace(service),
                             returnResponse
                );
            });
        });
    }

    void shimErrors(GoWriter writer) {
        for (final var error : model.getShapesWithTrait(ErrorTrait.class)) {
            writer.write("""
                                 case $L.$L:
                                      return Wrappers.Companion_Result_.Create_Failure_($L.$L(native_error.($L.$L)))
                                           
                                                
                                 """,
                         SmithyNameResolver.smithyTypesNamespace(error),
                         symbolProvider.toSymbol(error).getName(), SmithyNameResolver.shapeNamespace(error),
                         SmithyNameResolver.getToDafnyMethodName(service, error, ""), SmithyNameResolver.smithyTypesNamespace(error),
                         symbolProvider.toSymbol(error).getName());
        }
    }

    void resourceErrors(GoWriter writer) {
        for (final var error : model.getShapesWithTrait(ErrorTrait.class)) {
            writer.write("""
                                 case $L:
                                      return Wrappers.Companion_Result_.Create_Failure_($L(native_error.($L)))
                                           
                                                
                                 """,
                         SmithyNameResolver.getSmithyType(error, symbolProvider.toSymbol(error)), SmithyNameResolver.getToDafnyMethodName(service, error, ""),
                         SmithyNameResolver.getSmithyType(error, symbolProvider.toSymbol(error)));
        }
    }

    void generateUnmodelledErrors(GenerationContext context) {
        writerDelegator.useFileWriter("%s/unmodelled_errors.go".formatted(SmithyNameResolver.smithyTypesNamespace(service)), SmithyNameResolver.smithyTypesNamespace(service), writer -> {
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.write("""
                                 type CollectionOfErrors struct {
                                     ListOfErrors []error
                                     Message string
                                 }
                                                                  
                                 func (e CollectionOfErrors) Error() string {
                                 	return fmt.Sprintf("message: %s\\n err %v", e.Message, e.ListOfErrors)
                                 }
                                                                  
                                 type OpaqueError struct {
                                    ErrObject interface{}
                                 }
                                                                  
                                 func (e OpaqueError) Error() string {
                                    return fmt.Sprintf("message: %v", e.ErrObject )
                                 }
                                 """);
        });
    }

    void generateUnboundedStructures(GenerationContext context) {
        final var serviceOperationShapes = model.getServiceShapes().stream()
                                                  .map(topDownIndex::getContainedOperations)
                                                  .flatMap(Collection::stream)
                                                  .map(OperationShape::toShapeId)
                                                  .collect(Collectors.toSet());
        final var nonServiceOperationShapes = model.getOperationShapes()
                                                     .stream()
                                                     .map(Shape::getId)
                                                     .filter(operationShapeId -> operationShapeId.getNamespace()
                                                                                                 .equals(service.getId().getNamespace()))
                                                     .collect(Collectors.toSet());
        nonServiceOperationShapes.removeAll(serviceOperationShapes);
        for (final var operationShapeId : nonServiceOperationShapes) {
            OperationShape operationShape = model.expectShape(operationShapeId, OperationShape.class);
            StructureShape inputShape = model.expectShape(operationShape.getInputShape(), StructureShape.class);
            writerDelegator.useShapeWriter(inputShape, w -> new StructureGenerator(model, symbolProvider, w, inputShape).run());
            StructureShape outputShape = model.expectShape(operationShape.getOutputShape(), StructureShape.class);
            writerDelegator.useShapeWriter(outputShape, w -> new StructureGenerator(model, symbolProvider, w, outputShape).run());
        }
    }

    void generateReferencedResources(GenerationContext context) {
        var refResources = model.getShapesWithTrait(ReferenceTrait.class);
        for (final var refResource : refResources) {
            if (!refResource.expectTrait(ReferenceTrait.class).isService()) {
                final var resource = refResource.expectTrait(ReferenceTrait.class).getReferentId();
                final var resourceShape = model.expectShape(resource);

                if (!service.toShapeId().getNamespace().equals(resource.getNamespace())) {

                    continue;
                }
                writerDelegator.useFileWriter("%s/types.go".formatted(SmithyNameResolver.smithyTypesNamespace(service)), SmithyNameResolver.smithyTypesNamespace(service), writer -> {
                    writer.write("""
                                         type I$L interface {
                                         ${C|}
                                         }
                                         """, resource.getName(), writer.consumer((w) -> {
                        model.expectShape(resource, ResourceShape.class).getOperations().forEach(operation -> {
                            var operationShape = model.expectShape(operation, OperationShape.class);
                            w.write("""
                                            $L($L) (*$L, error)
                                            """, operationShape.getId().getName(), operationShape.getInputShape().getName(), operationShape.getOutputShape().getName());
                        });
                    }));
                });

                if (model.expectShape(resource, ResourceShape.class).hasTrait(ExtendableTrait.class)) {
                    generateNativeResourceWrapper(context, model.expectShape(resource, ResourceShape.class));
                }

                writerDelegator.useFileWriter("%s/%s.go".formatted(SmithyNameResolver.shapeNamespace(service), resource.getName()), SmithyNameResolver.shapeNamespace(service), writer -> {
                    writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(context.settings().getService().getNamespace()), SmithyNameResolver.smithyTypesNamespace(service));
                    writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(resource.getNamespace()), DafnyNameResolver.dafnyTypesNamespace(resourceShape));
                    writer.write("""
                                         type %s struct {
                                             impl %s.I%s
                                         }
                                         """.formatted(resource.getName(), DafnyNameResolver.dafnyTypesNamespace(resourceShape), resource.getName()));

                    model.expectShape(resource, ResourceShape.class).getOperations().forEach(operation -> {
                        final var operationShape = model.expectShape(operation, OperationShape.class);
                        final var inputShape = model.expectShape(operationShape.getInputShape());


                        final var outputShape = model.expectShape(operationShape.getOutputShape());
                        final var inputType = inputShape.hasTrait(UnitTypeTrait.class) ? "" : "params %s".formatted(SmithyNameResolver.getSmithyType(inputShape, symbolProvider.toSymbol(inputShape)));
                        final var outputType = outputShape.hasTrait(UnitTypeTrait.class) ? "" : "*%s".formatted(SmithyNameResolver.getSmithyType(outputShape, symbolProvider.toSymbol(outputShape)));

                        String baseClientCall;
                        if (inputShape.hasTrait(UnitTypeTrait.class)) {
                            baseClientCall = "var dafny_response = this.impl.%s()".formatted(operationShape.getId().getName());
                        } else {
                            baseClientCall = """
                                    var dafny_request %s = %s(params)
                                    var dafny_response = this.impl.%s(dafny_request)
                                    """.formatted(DafnyNameResolver.getDafnyType(inputShape, symbolProvider.toSymbol(inputShape)),
                                                  SmithyNameResolver.getToDafnyMethodName(service, inputShape, ""), operationShape.getId().getName());
                        }

                        String returnResponse, returnError;
                        if (outputShape.hasTrait(UnitTypeTrait.class)) {
                            returnResponse = "return nil";
                            returnError = "return";
                        } else {
                            returnResponse = """
                                    var native_response = %s(dafny_response.Extract().(%s))
                                    return &native_response, nil
                                    """.formatted(SmithyNameResolver.getFromDafnyMethodName(service, outputShape, ""),
                                                  DafnyNameResolver.getDafnyType(inputShape, symbolProvider.toSymbol(outputShape)));
                            returnError = "return nil,";
                        }


                        writer.write("""
                                               func (this *$L) $L($L) ($L, error) {
                                                   $L
                                                   if (dafny_response.Is_Failure()) {
                                                       err := dafny_response.Dtor_error().($L.Error);
                                                       ${C|}
                                                       if err.Is_CollectionOfErrors() {
                                                           $L CollectionOfErrors_Output_FromDafny(err)
                                                       }
                                                       if err.Is_Opaque() {
                                                           $L OpaqueError_Output_FromDafny(err)
                                                       }
                                                   }
                                                   $L
                                               }
                                             """,
                                     resource.getName(),
                                     operationShape.getId().getName(),
                                     inputType, outputType,
                                     baseClientCall,
                                     DafnyNameResolver.dafnyTypesNamespace(service),
                                     writer.consumer(w -> {
                                         for (var errorShape :
                                                 model.getShapesWithTrait(ErrorTrait.class)) {
                                             w.write("""
                                                                                    if err.Is_$L() {
                                                                                    $L $L(err)
                                                                                }
                                                             """, errorShape.toShapeId().getName(), returnError, SmithyNameResolver.getFromDafnyMethodName(service, errorShape, ""));
                                         }
                                     }), returnError, returnError, returnResponse
                        );
                    });
                });
            } else {
                //Generate Service
            }
        }
    }

    void generateNativeResourceWrapper(GenerationContext context, ResourceShape resourceShape) {
        writerDelegator.useFileWriter("%s/NativeWrapper.go".formatted(SmithyNameResolver.shapeNamespace(service)), SmithyNameResolver.shapeNamespace(service), writer -> {
            writer.addImportFromModule(context.settings().getModuleName(), SmithyNameResolver.smithyTypesNamespace(service));
            writer.addImportFromModule("github.com/dafny-lang/DafnyStandardLibGo", "Wrappers");
            writer.addImportFromModule(SmithyNameResolver.getGoModuleNameForSmithyNamespace(resourceShape.toShapeId().getNamespace()), DafnyNameResolver.dafnyTypesNamespace(resourceShape));

            writer.write("""
                                 type NativeWrapper struct {
                                     %s.I%s
                                     Impl %s.I%s
                                 }
                                 """.formatted(DafnyNameResolver.dafnyTypesNamespace(resourceShape), resourceShape.getId().getName(), SmithyNameResolver.smithyTypesNamespace(resourceShape), resourceShape.getId().getName()));

            writer.write("""
                                 func ToNative$L(dafnyResource $L.I$L)($L.I$L) {
                                     val, ok := dafnyResource.(*NativeWrapper)
                                     if ok {
                                         return val.Impl
                                     }
                                     return &$L{dafnyResource}
                                 }
                                 """, resourceShape.getId().getName(), DafnyNameResolver.dafnyTypesNamespace(resourceShape), resourceShape.getId().getName(), SmithyNameResolver.smithyTypesNamespace(resourceShape), resourceShape.getId().getName(), resourceShape.getId().getName());

            writer.write("""
                                 func ToDafny$L(nativeResource $L.I$L) $L.I$L {
                                     return nativeResource.(*$L).impl
                                 }
                                 """, resourceShape.getId().getName(), SmithyNameResolver.smithyTypesNamespace(resourceShape), resourceShape.getId().getName(), DafnyNameResolver.dafnyTypesNamespace(resourceShape), resourceShape.getId().getName(), resourceShape.getId().getName());

            resourceShape.getOperations().forEach(operation -> {
                final var operationShape = model.expectShape(operation, OperationShape.class);
                final var inputShape = model.expectShape(operationShape.getInputShape());
                final var outputShape = model.expectShape(operationShape.getOutputShape());
                final var inputType = inputShape.hasTrait(UnitTypeTrait.class) ? "" : "input %s".formatted(DafnyNameResolver.getDafnyType(resourceShape, symbolProvider.toSymbol(inputShape)));
                final var outputType = outputShape.hasTrait(UnitTypeTrait.class) ? "" : "*%s,".formatted(SmithyNameResolver.getSmithyType(outputShape, symbolProvider.toSymbol(outputShape)));

                final var typeConversion = inputShape.hasTrait(UnitTypeTrait.class) ? "" : "var native_request = %s(input)".formatted(SmithyNameResolver.getFromDafnyMethodName(service, inputShape, ""));
                final var clientCall = "this.Impl.%s(%s)".formatted(operationShape.getId().getName(), inputShape.hasTrait(UnitTypeTrait.class) ? "" : "native_request");
                String clientResponse, returnResponse;
                if (outputShape.hasTrait(UnitTypeTrait.class)) {
                    clientResponse = "var native_error";
                    returnResponse = "dafny.TupleOf()";
                    writer.addImportFromModule("github.com/dafny-lang/DafnyRuntimeGo", "dafny");
                } else {
                    clientResponse = "var native_response, native_error";
                    returnResponse = "%s(*native_response)".formatted(
                                                                         SmithyNameResolver.getToDafnyMethodName(service, outputShape, ""));
                }
                writer.write("""
                                       func (this *NativeWrapper) $L($L) Wrappers.Result {
                                           $L
                                           $L = $L
                                           if native_error != nil {
                                               switch native_error.(type) {
                                                ${C|}
                                                case $L.CollectionOfErrors:
                                                    return Wrappers.Companion_Result_.Create_Failure_(CollectionOfErrors_Input_ToDafny(native_error.($L.CollectionOfErrors)))
                                                default:
                                                    return Wrappers.Companion_Result_.Create_Failure_(OpaqueError_Input_ToDafny(native_error.($L.OpaqueError)))
                                                }
                                           }
                                           return Wrappers.Companion_Result_.Create_Success_($L)
                                       }
                                     """,
                             operationShape.getId().getName(),
                             inputType, typeConversion, clientResponse, clientCall,
                             writer.consumer(w -> resourceErrors(w)), SmithyNameResolver.smithyTypesNamespace(service), SmithyNameResolver.smithyTypesNamespace(service), SmithyNameResolver.smithyTypesNamespace(service),
                             returnResponse
                );
            });
        });
    }
}