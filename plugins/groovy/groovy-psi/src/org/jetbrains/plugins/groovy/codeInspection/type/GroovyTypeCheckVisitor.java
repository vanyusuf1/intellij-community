// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.codeInspection.type;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;
import org.jetbrains.plugins.groovy.GroovyBundle;
import org.jetbrains.plugins.groovy.annotator.GrHighlightUtil;
import org.jetbrains.plugins.groovy.annotator.intentions.QuickfixUtil;
import org.jetbrains.plugins.groovy.codeInspection.BaseInspectionVisitor;
import org.jetbrains.plugins.groovy.codeInspection.GroovyInspectionBundle;
import org.jetbrains.plugins.groovy.codeInspection.assignment.*;
import org.jetbrains.plugins.groovy.codeInspection.type.highlighting.*;
import org.jetbrains.plugins.groovy.codeInspection.untypedUnresolvedAccess.requests.CreateMethodFromUsageKt;
import org.jetbrains.plugins.groovy.config.GroovyConfigUtils;
import org.jetbrains.plugins.groovy.extensions.GroovyNamedArgumentProvider;
import org.jetbrains.plugins.groovy.extensions.NamedArgumentDescriptor;
import org.jetbrains.plugins.groovy.extensions.NamedArgumentUtilKt;
import org.jetbrains.plugins.groovy.highlighting.HighlightSink;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.api.GrFunctionalExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrListOrMap;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrConstructorInvocation;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariableDeclaration;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrNamedArgument;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrReturnStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrThrowStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.clauses.GrForInClause;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrString;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrIndexProperty;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameterList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrBuilderMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrEnumConstant;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrGdkMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrClosureType;
import org.jetbrains.plugins.groovy.lang.psi.impl.PsiImplUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.signatures.GrClosureSignatureUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.ConversionResult;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.DefaultConstructor;
import org.jetbrains.plugins.groovy.lang.psi.typeEnhancers.ClosureParameterEnhancer;
import org.jetbrains.plugins.groovy.lang.psi.typeEnhancers.ClosureParamsEnhancer;
import org.jetbrains.plugins.groovy.lang.psi.typeEnhancers.GrTypeConverter.Position;
import org.jetbrains.plugins.groovy.lang.psi.util.*;
import org.jetbrains.plugins.groovy.lang.resolve.ResolveUtil;
import org.jetbrains.plugins.groovy.lang.resolve.api.Applicability;
import org.jetbrains.plugins.groovy.lang.resolve.api.Argument;
import org.jetbrains.plugins.groovy.lang.resolve.api.GroovyCallReference;
import org.jetbrains.plugins.groovy.lang.resolve.api.GroovyMethodCallReference;

import java.util.*;

import static com.intellij.psi.util.PsiUtil.extractIterableTypeParameter;
import static java.util.Arrays.asList;
import static org.jetbrains.plugins.groovy.codeInspection.type.GroovyTypeCheckVisitorHelper.*;
import static org.jetbrains.plugins.groovy.codeInspection.utils.ControlFlowUtils.isImplicitReturnStatement;
import static org.jetbrains.plugins.groovy.lang.psi.util.PsiUtilKt.isFake;

public class GroovyTypeCheckVisitor extends BaseInspectionVisitor {

  private static final Logger LOG = Logger.getInstance(GroovyAssignabilityCheckInspection.class);

  private final HighlightSink myHighlightSink = new HighlightSink() {
    @Override
    public void registerProblem(@NotNull PsiElement highlightElement,
                                @NotNull ProblemHighlightType highlightType,
                                @NotNull String message,
                                @NotNull LocalQuickFix... fixes) {
      GroovyTypeCheckVisitor.this.registerError(highlightElement, message, fixes, highlightType);
    }
  };

  private boolean checkCallApplicability(@Nullable PsiType type,
                                         boolean checkUnknownArgs,
                                         @NotNull CallInfo<? extends GroovyPsiElement> info) {

    PsiType[] argumentTypes = info.getArgumentTypes();
    GrExpression invoked = info.getInvokedExpression();
    if (invoked == null) return true;

    if (type instanceof GrClosureType) {
      if (argumentTypes == null) return true;

      Applicability result =
        PsiUtil.isApplicableConcrete(argumentTypes, (GrClosureType)type, info.getCall());
      switch (result) {
        case inapplicable:
          registerCannotApplyError(invoked.getText(), info);
          return false;
        case canBeApplicable:
          if (checkUnknownArgs) {
            highlightUnknownArgs(info);
          }
          return !checkUnknownArgs;
        default:
          return true;
      }
    }
    else if (type != null) {
      final GroovyResolveResult[] calls = ResolveUtil.getMethodCandidates(type, "call", invoked, argumentTypes);
      for (GroovyResolveResult result : calls) {
        PsiElement resolved = result.getElement();
        if (resolved instanceof PsiMethod && !result.isInvokedOnProperty()) {
          if (!checkMethodApplicability(result, checkUnknownArgs, info)) return false;
        }
        else if (resolved instanceof PsiField) {
          if (!checkCallApplicability(((PsiField)resolved).getType(), checkUnknownArgs && calls.length == 1, info)) return false;
        }
      }
      if (calls.length == 0 && !(invoked instanceof GrString)) {
        registerCannotApplyError(invoked.getText(), info);
      }
      return true;
    }
    return true;
  }

  private boolean checkCannotInferArgumentTypes(@NotNull CallInfo info) {
    if (info.getArgumentTypes() != null) {
      return true;
    }
    else {
      highlightUnknownArgs(info);
      return false;
    }
  }

  private <T extends GroovyPsiElement> boolean checkConstructorApplicability(@NotNull GroovyResolveResult constructorResolveResult,
                                                                             @NotNull CallInfo<T> info,
                                                                             boolean checkUnknownArgs) {
    final PsiElement element = constructorResolveResult.getElement();
    LOG.assertTrue(element instanceof PsiMethod && ((PsiMethod)element).isConstructor(), element);
    final PsiMethod constructor = (PsiMethod)element;

    final GrArgumentList argList = info.getArgumentList();
    if (argList != null) {
      final GrExpression[] exprArgs = argList.getExpressionArguments();
      if (exprArgs.length == 0 && !PsiUtil.isConstructorHasRequiredParameters(constructor)) return true;
    }

    PsiType[] types = info.getArgumentTypes();
    PsiClass containingClass = constructor.getContainingClass();
    if (types != null && containingClass != null) {
      final PsiType[] newTypes = GrInnerClassConstructorUtil.addEnclosingArgIfNeeded(types, info.getCall(), containingClass);
      if (newTypes.length != types.length) {
        return checkMethodApplicability(constructorResolveResult, checkUnknownArgs, new DelegatingCallInfo<T>(info) {
          @NotNull
          @Override
          public PsiType[] getArgumentTypes() {
            return newTypes;
          }
        });
      }
    }

    return checkMethodApplicability(constructorResolveResult, checkUnknownArgs, info);
  }

  private void processConstructorCall(@NotNull GrListOrMapInfo info) {
    if (hasErrorElements(info.getArgumentList())) return;

    if (!checkCannotInferArgumentTypes(info)) return;

    GroovyResolveResult[] results = info.multiResolve();
    boolean checkUnknowkArgs = results.length == 1;

    for (GroovyResolveResult result : results) {
      PsiElement resolved = result.getElement();
      if (resolved instanceof PsiMethod) {
        if (!checkConstructorApplicability(result, info, checkUnknowkArgs)) return;
      }
    }

    if (results.length > 1) {
      registerError(
        info.getElementToHighlight(),
        GroovyBundle.message("constructor.call.is.ambiguous"),
        null,
        ProblemHighlightType.GENERIC_ERROR
      );
    }

    checkNamedArgumentsType(info);
  }

  private boolean checkForImplicitEnumAssigning(@Nullable PsiType expectedType,
                                                @NotNull GrExpression expression,
                                                @NotNull GroovyPsiElement elementToHighlight) {
    if (!(expectedType instanceof PsiClassType)) return false;

    if (!GroovyConfigUtils.getInstance().isVersionAtLeast(elementToHighlight, GroovyConfigUtils.GROOVY1_8)) return false;

    final PsiClass resolved = ((PsiClassType)expectedType).resolve();
    if (resolved == null || !resolved.isEnum()) return false;

    final PsiType type = expression.getType();
    if (type == null) return false;

    if (!type.equalsToText(GroovyCommonClassNames.GROOVY_LANG_GSTRING) &&
        !type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
      return false;
    }

    final Object result = GroovyConstantExpressionEvaluator.evaluate(expression);
    if (!(result instanceof String)) {
      registerError(
        elementToHighlight,
        ProblemHighlightType.WEAK_WARNING,
        GroovyBundle.message("cannot.assign.string.to.enum.0", expectedType.getPresentableText())
      );
    }
    else {
      final PsiField field = resolved.findFieldByName((String)result, true);
      if (!(field instanceof PsiEnumConstant)) {
        registerError(
          elementToHighlight,
          ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
          GroovyBundle.message("cannot.find.enum.constant.0.in.enum.1", result, expectedType.getPresentableText())
        );
      }
    }
    return true;
  }

  private <T extends GroovyPsiElement> boolean checkMethodApplicability(@NotNull final GroovyResolveResult methodResolveResult,
                                                                        boolean checkUnknownArgs,
                                                                        @NotNull final CallInfo<T> info) {
    final PsiElement element = methodResolveResult.getElement();
    if (!(element instanceof PsiMethod)) return true;
    if (element instanceof GrBuilderMethod) return true;

    final PsiMethod method = (PsiMethod)element;

    Applicability applicable = calcApplicability(method, methodResolveResult.getSubstitutor(), info);

    switch (applicable) {
      case inapplicable:
        highlightInapplicableMethodUsage(methodResolveResult, info, method);
        return false;
      case canBeApplicable:
        if (checkUnknownArgs) {
          highlightUnknownArgs(info);
        }
        return !checkUnknownArgs;
      default:
        return true;
    }
  }

  private static <T extends GroovyPsiElement> Applicability calcApplicability(@NotNull PsiMethod method,
                                                                              @NotNull PsiSubstitutor substitutor,
                                                                              @NotNull CallInfo<T> info) {
    if ("call".equals(method.getName()) && info.getInvokedExpression() instanceof GrReferenceExpression) {
      final GrExpression qualifierExpression = ((GrReferenceExpression)info.getInvokedExpression()).getQualifierExpression();
      if (qualifierExpression != null) {
        final PsiType type = qualifierExpression.getType();
        if (type instanceof GrClosureType) {
          return PsiUtil.isApplicableConcrete(info.getArgumentTypes(), (GrClosureType)type, info.getInvokedExpression());
        }
      }
    }
    return PsiUtil.isApplicableConcrete(info.getArgumentTypes(), method, substitutor, info.getCall(), false);
  }

  @Override
  public void visitMethodCall(@NotNull GrMethodCall call) {
    super.visitMethodCall(call); // visitExpression

    if (isFake(call)) return;

    GrMethodCallInfo info = new GrMethodCallInfo(call);
    if (hasErrorElements(info.getArgumentList())) return;

    if (info.getInvokedExpression() instanceof GrReferenceExpression) {
      final GrReferenceExpression referenceExpression = (GrReferenceExpression)info.getInvokedExpression();
      GroovyResolveResult resolveResult = info.advancedResolve();
      GroovyResolveResult[] results = info.multiResolve();

      PsiElement resolved = resolveResult.getElement();
      if (resolved == null) {
        GrExpression qualifier = referenceExpression.getQualifierExpression();
        if (qualifier == null && GrHighlightUtil.isDeclarationAssignment(referenceExpression)) return;
      }

      if (!checkCannotInferArgumentTypes(info)) return;

      if (resolved != null) {
        if (resolved instanceof PsiMethod && !resolveResult.isInvokedOnProperty()) {
          GroovyMethodCallReference reference = call.getImplicitCallReference();
          if (reference != null) {
            Argument receiver = reference.getReceiverArgument();
            PsiType receiverType = receiver != null ? receiver.getType() : null;
            checkCallApplicability(receiverType, true, info);
          }
          else {
            checkMethodApplicability(resolveResult, true, info);
          }
        }
        else {
          checkCallApplicability(referenceExpression.getType(), true, info);
        }
      }
      else if (results.length > 0) {
        PsiType[] argumentTypes = info.getArgumentTypes();
        boolean checkUnknownArgs = argumentTypes == null || ContainerUtil.or(argumentTypes, Objects::isNull);
        for (GroovyResolveResult result : results) {
          PsiElement current = result.getElement();
          if (current instanceof PsiMethod && !result.isInvokedOnProperty()) {
            if (!checkMethodApplicability(result, checkUnknownArgs, info)) return;
          }
          else {
            if (!checkCallApplicability(referenceExpression.getType(), checkUnknownArgs, info)) return;
          }
        }

        registerError(info.getElementToHighlight(), ProblemHighlightType.GENERIC_ERROR, GroovyBundle.message("method.call.is.ambiguous"));
      }
    }
    else if (info.getInvokedExpression() != null) { //it checks in visitRefExpr(...)
      final PsiType type = info.getInvokedExpression().getType();
      checkCallApplicability(type, true, info);
    }

    checkNamedArgumentsType(info);
  }

  private void checkNamedArgumentsType(@NotNull CallInfo<?> info) {
    checkNamedArgumentsType(info.getCall());
  }

  private void checkNamedArgumentsType(@NotNull GroovyPsiElement rawCall) {
    if (!(rawCall instanceof GrCall)) return;
    GrCall call = (GrCall)rawCall;

    GrNamedArgument[] namedArguments = PsiUtil.getFirstMapNamedArguments(call);

    if (namedArguments.length == 0) return;

    Map<String, NamedArgumentDescriptor> map = GroovyNamedArgumentProvider.getNamedArgumentsFromAllProviders(call, null, false);
    if (map == null) return;

    checkNamedArguments(call, namedArguments, map);
  }

  private void checkNamedArguments(GroovyPsiElement context, GrNamedArgument[] namedArguments, Map<String, NamedArgumentDescriptor> map) {
    for (GrNamedArgument namedArgument : namedArguments) {
      String labelName = namedArgument.getLabelName();

      NamedArgumentDescriptor descriptor = map.get(labelName);

      if (descriptor == null) continue;

      GrExpression namedArgumentExpression = namedArgument.getExpression();
      if (namedArgumentExpression == null) continue;

      if (getTupleInitializer(namedArgumentExpression) != null) continue;

      if (PsiUtil.isRawClassMemberAccess(namedArgumentExpression)) continue;

      PsiType expressionType =
        TypesUtil.boxPrimitiveType(namedArgumentExpression.getType(), context.getManager(), context.getResolveScope());
      if (expressionType == null) continue;

      if (!descriptor.checkType(expressionType, context)) {
        registerError(
          namedArgumentExpression,
          ProblemHighlightType.GENERIC_ERROR,
          "Type of argument '" + labelName + "' can not be '" + expressionType.getPresentableText() + "'"
        );
      }
    }
  }

  private void highlightInapplicableMethodUsage(@NotNull GroovyResolveResult methodResolveResult,
                                                @NotNull CallInfo info,
                                                @NotNull PsiMethod method) {
    final PsiClass containingClass =
      method instanceof GrGdkMethod ? ((GrGdkMethod)method).getStaticMethod().getContainingClass() : method.getContainingClass();

    PsiType[] argumentTypes = info.getArgumentTypes();
    if (containingClass == null) {
      registerCannotApplyError(method.getName(), info);
      return;
    }
    final String message;
    if (method instanceof DefaultConstructor) {
      message = GroovyBundle.message("cannot.apply.default.constructor", method.getName());
    }
    else {
      final String typesString = buildArgTypesList(argumentTypes);
      final PsiElementFactory factory = JavaPsiFacade.getElementFactory(method.getProject());
      final PsiClassType containingType = factory.createType(containingClass, methodResolveResult.getSubstitutor());
      final String canonicalText = containingType.getInternalCanonicalText();
      if (method.isConstructor()) {
        message = GroovyBundle.message("cannot.apply.constructor", method.getName(), canonicalText, typesString);
      }
      else {
        message = GroovyBundle.message("cannot.apply.method1", method.getName(), canonicalText, typesString);
      }
    }

    LocalQuickFix[] castFixes =
      genCastFixes(GrClosureSignatureUtil.createSignature(methodResolveResult), argumentTypes, info.getArgumentList());
    List<LocalQuickFix> fixes = new ArrayList<>(asList(castFixes));

    GroovyPsiElement call = info.getCall();
    if (call instanceof GrMethodCall){
      List<IntentionAction> actions = CreateMethodFromUsageKt.generateCreateMethodActions((GrMethodCall)call);
      fixes.addAll(asList(QuickfixUtil.intentionsToFixes(call, actions)));
    }

    registerError(
      info.getElementToHighlight(),
      message,
      fixes.toArray(LocalQuickFix.EMPTY_ARRAY),
      ProblemHighlightType.GENERIC_ERROR
    );
  }

  private void highlightUnknownArgs(@NotNull CallInfo info) {
    registerError(
      info.getElementToHighlight(),
      GroovyBundle.message("cannot.infer.argument.types"),
      LocalQuickFix.EMPTY_ARRAY,
      ProblemHighlightType.WEAK_WARNING
    );
  }

  private void processAssignment(@NotNull PsiType expectedType,
                                 @NotNull GrExpression expression,
                                 @NotNull PsiElement toHighlight,
                                 @NotNull PsiElement context) {
    checkPossibleLooseOfPrecision(expectedType, expression, toHighlight);

    processAssignment(expectedType, expression, toHighlight, "cannot.assign", context, Position.ASSIGNMENT);
  }

  private void processAssignment(@NotNull PsiType expectedType,
                                 @NotNull GrExpression expression,
                                 @NotNull PsiElement toHighlight,
                                 @NotNull @PropertyKey(resourceBundle = GroovyBundle.BUNDLE) String messageKey,
                                 @NotNull PsiElement context,
                                 @NotNull Position position) {
    { // check if  current assignment is constructor call
      final GrListOrMap initializer = getTupleInitializer(expression);
      if (initializer != null) {
        processConstructorCall(new GrListOrMapInfo(initializer));
        return;
      }
    }

    if (PsiUtil.isRawClassMemberAccess(expression)) return;
    if (checkForImplicitEnumAssigning(expectedType, expression, expression)) return;

    final PsiType actualType = expression.getType();
    if (actualType == null) return;

    final ConversionResult result = TypesUtil.canAssign(expectedType, actualType, context, position);
    if (result == ConversionResult.OK) return;

    final List<LocalQuickFix> fixes = new ArrayList<>();
    {
      fixes.add(new GrCastFix(expectedType, expression));
      final String varName = getLValueVarName(toHighlight);
      if (varName != null) {
        fixes.add(new GrChangeVariableType(actualType, varName));
      }
    }

    final String message = GroovyBundle.message(messageKey, actualType.getPresentableText(), expectedType.getPresentableText());
    registerError(
      toHighlight,
      message,
      fixes.toArray(LocalQuickFix.EMPTY_ARRAY),
      result == ConversionResult.ERROR ? ProblemHighlightType.GENERIC_ERROR : ProblemHighlightType.GENERIC_ERROR_OR_WARNING
    );
  }


  private void processAssignment(@NotNull PsiType lType,
                                 @Nullable PsiType rType,
                                 @NotNull GroovyPsiElement context,
                                 @NotNull PsiElement elementToHighlight) {
    if (rType == null) return;
    final ConversionResult result = TypesUtil.canAssign(lType, rType, context, Position.ASSIGNMENT);
    processResult(result, elementToHighlight, "cannot.assign", lType, rType, LocalQuickFix.EMPTY_ARRAY);
  }

  protected void processAssignmentWithinMultipleAssignment(@Nullable PsiType targetType,
                                                           @Nullable PsiType actualType,
                                                           @NotNull PsiElement context,
                                                           @NotNull PsiElement elementToHighlight) {
    if (targetType == null || actualType == null) return;
    final ConversionResult result = TypesUtil.canAssignWithinMultipleAssignment(targetType, actualType);
    if (result == ConversionResult.OK) return;
    registerError(
      elementToHighlight,
      GroovyBundle.message("cannot.assign", actualType.getPresentableText(), targetType.getPresentableText()),
      LocalQuickFix.EMPTY_ARRAY,
      result == ConversionResult.ERROR ? ProblemHighlightType.GENERIC_ERROR : ProblemHighlightType.GENERIC_ERROR_OR_WARNING
    );
  }

  @Override
  public void visitTupleAssignmentExpression(@NotNull GrTupleAssignmentExpression expression) {
    super.visitTupleAssignmentExpression(expression); // visitExpression

    GrExpression initializer = expression.getRValue();
    if (initializer == null) return;

    GrTuple tupleExpression = expression.getLValue();
    GrExpression[] lValues = tupleExpression.getExpressions();
    if (initializer instanceof GrListOrMap) {
      GrExpression[] initializers = ((GrListOrMap)initializer).getInitializers();
      for (int i = 0; i < lValues.length; i++) {
        GrExpression lValue = lValues[i];
        if (initializers.length <= i) break;
        GrExpression rValue = initializers[i];
        processAssignmentWithinMultipleAssignment(lValue.getType(), rValue.getType(), expression, rValue);
      }
    }
    else {
      PsiType type = initializer.getType();
      PsiType rType = extractIterableTypeParameter(type, false);

      for (GrExpression lValue : lValues) {
        PsiType lType = lValue.getNominalType();
        // For assignments with spread dot
        if (PsiImplUtil.isSpreadAssignment(lValue)) {
          final PsiType argType = extractIterableTypeParameter(lType, false);
          if (argType != null && rType != null) {
            processAssignment(argType, rType, tupleExpression, getExpressionPartToHighlight(lValue));
          }
          return;
        }
        if (lValue instanceof GrReferenceExpression && ((GrReferenceExpression)lValue).resolve() instanceof GrReferenceExpression) {
          //lvalue is not-declared variable
          return;
        }

        if (lType != null && rType != null) {
          processAssignment(lType, rType, tupleExpression, getExpressionPartToHighlight(lValue));
        }
      }
    }
  }

  private void processResult(@NotNull ConversionResult result,
                             @NotNull PsiElement elementToHighlight,
                             @NotNull @PropertyKey(resourceBundle = GroovyBundle.BUNDLE) String messageKey,
                             @NotNull PsiType lType,
                             @NotNull PsiType rType,
                             @NotNull LocalQuickFix[] fixes) {
    if (result == ConversionResult.OK) return;
    registerError(
      elementToHighlight,
      GroovyBundle.message(messageKey, rType.getPresentableText(), lType.getPresentableText()),
      fixes,
      result == ConversionResult.ERROR ? ProblemHighlightType.GENERIC_ERROR : ProblemHighlightType.GENERIC_ERROR_OR_WARNING
    );
  }

  protected void processReturnValue(@NotNull GrExpression expression,
                                    @NotNull PsiElement context,
                                    @NotNull PsiElement elementToHighlight) {
    if (getTupleInitializer(expression) != null) return;
    final PsiType returnType = PsiImplUtil.inferReturnType(expression);
    if (returnType == null || PsiType.VOID.equals(returnType)) return;
    processAssignment(returnType, expression, elementToHighlight, "cannot.return.type", context, Position.RETURN_VALUE);
  }

  private void registerCannotApplyError(@NotNull String invokedText, @NotNull CallInfo info) {
    if (info.getArgumentTypes() == null) return;
    final String typesString = buildArgTypesList(info.getArgumentTypes());
    registerError(
      info.getElementToHighlight(),
      ProblemHighlightType.GENERIC_ERROR,
      GroovyBundle.message("cannot.apply.method.or.closure", invokedText, typesString)
    );
  }

  @Override
  protected void registerError(@NotNull PsiElement location,
                               @NotNull String description,
                               @Nullable LocalQuickFix[] fixes,
                               ProblemHighlightType highlightType) {
    if (PsiUtil.isCompileStatic(location)) {
      // filter all errors here, error will be highlighted by annotator
      if (highlightType != ProblemHighlightType.GENERIC_ERROR) {
        super.registerError(location, description, fixes, highlightType);
      }
    }
    else {
      if (highlightType == ProblemHighlightType.GENERIC_ERROR) {
        // if this visitor works within non-static context we will highlight all errors as warnings
        super.registerError(location, description, fixes, ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
      }
      else {
        // if this visitor works within static context errors will be highlighted as errors by annotator, warnings will be highlighted as warnings here
        super.registerError(location, description, fixes, highlightType);
      }
    }
  }

  @Override
  public void visitReturnStatement(@NotNull GrReturnStatement returnStatement) {
    final GrExpression value = returnStatement.getReturnValue();
    if (value != null) {
      processReturnValue(value, returnStatement, returnStatement.getReturnWord());
    }
  }

  @Override
  public void visitThrowStatement(@NotNull GrThrowStatement throwStatement) {
    final GrExpression exception = throwStatement.getException();
    if (exception == null) return;
    final PsiElement throwWord = throwStatement.getFirstChild();
    processAssignment(
      PsiType.getJavaLangThrowable(
        throwStatement.getManager(),
        throwStatement.getResolveScope()
      ),
      exception,
      throwWord,
      throwWord
    );
  }

  @Override
  public void visitExpression(@NotNull GrExpression expression) {
    if (isImplicitReturnStatement(expression)) {
      processReturnValue(expression, expression, expression);
    }
  }

  @Override
  public void visitNewExpression(@NotNull GrNewExpression newExpression) {
    super.visitNewExpression(newExpression); // visitExpression

    if (hasErrorElements(newExpression) || hasErrorElements(newExpression.getArgumentList())) return;

    final GroovyCallReference reference = newExpression.getConstructorReference();
    if (reference == null) return;
    if (new GrNewExpressionHighlighter(newExpression, reference, myHighlightSink).highlight()) {
      return;
    }

    checkNamedArgumentsType(newExpression);
  }

  @Override
  public void visitEnumConstant(@NotNull GrEnumConstant enumConstant) {
    if (hasErrorElements(enumConstant) || hasErrorElements(enumConstant.getArgumentList())) return;

    if (new GrEnumConstantHighlighter(enumConstant, myHighlightSink).highlight()) {
      return;
    }

    checkNamedArgumentsType(enumConstant);
  }

  @Override
  public void visitConstructorInvocation(@NotNull GrConstructorInvocation invocation) {
    if (hasErrorElements(invocation) || hasErrorElements(invocation.getArgumentList())) return;

    if (new GrConstructorInvocationHighlighter(invocation, myHighlightSink).highlight()) {
      return;
    }
    checkNamedArgumentsType(invocation);
  }

  @Override
  public void visitAssignmentExpression(@NotNull GrAssignmentExpression assignment) {
    super.visitAssignmentExpression(assignment); // visitExpression

    if (assignment.isOperatorAssignment()) return;

    final GrExpression lValue = assignment.getLValue();
    if (!PsiUtil.mightBeLValue(lValue)) return;

    final GrExpression rValue = assignment.getRValue();
    if (rValue == null) return;

    if (lValue instanceof GrReferenceExpression && ((GrReferenceExpression)lValue).resolve() instanceof GrReferenceExpression) {
      //lvalue is not-declared variable
      return;
    }

    PsiType lValueNominalType = lValue.getNominalType();
    final PsiType targetType = PsiImplUtil.isSpreadAssignment(lValue) ? extractIterableTypeParameter(lValueNominalType, false)
                                                                      : lValueNominalType;
    if (targetType == null) return;
    processAssignment(targetType, rValue, lValue, assignment);
  }

  void checkPossibleLooseOfPrecision(@NotNull PsiType targetType, @NotNull GrExpression expression, @NotNull PsiElement toHighlight) {
    PsiType actualType = expression.getType();
    if (actualType == null) return;
    if (!PrecisionUtil.isPossibleLooseOfPrecision(targetType, actualType, expression)) return;
    registerError(
      toHighlight,
      GroovyBundle.message("loss.of.precision", actualType.getPresentableText(), targetType.getPresentableText()),
      new LocalQuickFix[]{new GrCastFix(targetType, expression, false)},
      ProblemHighlightType.GENERIC_ERROR
    );
  }

  @Override
  public void visitBinaryExpression(@NotNull GrBinaryExpression binary) {
    super.visitBinaryExpression(binary); // visitExpression

    GroovyCallReference reference = binary.getReference();
    if (reference == null) return;
    new BinaryExpressionHighlighter(binary, reference, myHighlightSink).highlight();
  }

  @Override
  public void visitCastExpression(@NotNull GrTypeCastExpression expression) {
    super.visitCastExpression(expression); // visitExpression

    final GrExpression operand = expression.getOperand();
    if (operand == null) return;
    final PsiType actualType = operand.getType();
    if (actualType == null) return;

    if (expression.getCastTypeElement() == null) return;
    final PsiType expectedType = expression.getCastTypeElement().getType();

    final ConversionResult result = TypesUtil.canCast(expectedType, actualType, expression);
    if (result == ConversionResult.OK) return;
    final ProblemHighlightType highlightType = result == ConversionResult.ERROR
                                               ? ProblemHighlightType.GENERIC_ERROR
                                               : ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
    final String message = GroovyBundle.message(
      "cannot.cast",
      actualType.getPresentableText(),
      expectedType.getPresentableText()
    );
    registerError(
      expression,
      highlightType,
      message
    );
  }

  @Override
  public void visitIndexProperty(@NotNull GrIndexProperty expression) {
    super.visitIndexProperty(expression); // visitExpression

    if (hasErrorElements(expression)) return;

    if (GroovyIndexPropertyUtil.isClassLiteral(expression)) return;
    if (GroovyIndexPropertyUtil.isSimpleArrayAccess(expression)) return;

    GrArgumentList argumentList = expression.getArgumentList();
    GroovyMethodCallReference rValueReference = expression.getRValueReference();
    if (rValueReference != null) {
      new GenericCallReferenceHighlighter(rValueReference, argumentList, myHighlightSink).highlightMethodApplicability();
    }
    GroovyMethodCallReference lValueReference = expression.getLValueReference();
    if (lValueReference != null) {
      new GenericCallReferenceHighlighter(lValueReference, argumentList, myHighlightSink).highlightMethodApplicability();
    }
  }

  /**
   * Handles method default values.
   */
  @Override
  public void visitMethod(@NotNull GrMethod method) {
    final PsiTypeParameter[] parameters = method.getTypeParameters();
    final Map<PsiTypeParameter, PsiType> map = new HashMap<>();
    for (PsiTypeParameter parameter : parameters) {
      final PsiClassType[] types = parameter.getSuperTypes();
      final PsiType bound = PsiIntersectionType.createIntersection(types);
      final PsiWildcardType wildcardType = PsiWildcardType.createExtends(method.getManager(), bound);
      map.put(parameter, wildcardType);
    }
    final PsiSubstitutor substitutor = PsiSubstitutor.createSubstitutor(map);

    for (GrParameter parameter : method.getParameterList().getParameters()) {
      final GrExpression initializer = parameter.getInitializerGroovy();
      if (initializer == null) continue;
      final PsiType targetType = parameter.getType();
      processAssignment(
        substitutor.substitute(targetType),
        initializer,
        parameter.getNameIdentifierGroovy(),
        parameter
      );
    }
  }

  @Override
  public void visitParameterList(@NotNull final GrParameterList parameterList) {
    PsiElement parent = parameterList.getParent();
    if (!(parent instanceof GrFunctionalExpression)) return;

    GrParameter[] parameters = parameterList.getParameters();
    if (parameters.length > 0) {
      List<PsiType[]> signatures = ClosureParamsEnhancer.findFittingSignatures((GrFunctionalExpression)parent); // TODO: suspicious method call
      final List<PsiType> paramTypes = ContainerUtil.map(parameters, parameter -> parameter.getType());

      if (signatures.size() > 1) {
        final PsiType[] fittingSignature = ContainerUtil.find(signatures, types -> {
          for (int i = 0; i < types.length; i++) {
            if (!TypesUtil.isAssignableByMethodCallConversion(paramTypes.get(i), types[i], parameterList)) {
              return false;
            }
          }
          return true;
        });
        if (fittingSignature == null) {
          registerError(
            parameterList,
            GroovyInspectionBundle.message("no.applicable.signature.found"),
            null,
            ProblemHighlightType.GENERIC_ERROR
          );
        }
      }
      else if (signatures.size() == 1) {
        PsiType[] types = signatures.get(0);
        for (int i = 0; i < types.length; i++) {
          GrTypeElement typeElement = parameters[i].getTypeElementGroovy();
          if (typeElement == null) continue;
          PsiType expected = types[i];
          PsiType actual = paramTypes.get(i);
          if (!TypesUtil.isAssignableByMethodCallConversion(actual, expected, parameterList)) {
            registerError(
              typeElement,
              GroovyInspectionBundle.message("expected.type.0", expected.getCanonicalText(false), actual.getCanonicalText(false)),
              null,
              ProblemHighlightType.GENERIC_ERROR
            );
          }
        }
      }
    }
  }

  @Override
  public void visitForInClause(@NotNull GrForInClause forInClause) {
    final GrVariable variable = forInClause.getDeclaredVariable();
    final GrExpression iterated = forInClause.getIteratedExpression();
    if (variable == null || iterated == null) return;

    final PsiType iteratedType = ClosureParameterEnhancer.findTypeForIteration(iterated, forInClause);
    if (iteratedType == null) return;
    final PsiType targetType = variable.getType();

    final ConversionResult result = TypesUtil.canAssign(targetType, iteratedType, forInClause, Position.ASSIGNMENT);
    LocalQuickFix[] fixes = {new GrCastFix(TypesUtil.createListType(iterated, targetType), iterated)};
    processResult(result, variable, "cannot.assign", targetType, iteratedType, fixes);
  }

  @Override
  public void visitVariable(@NotNull GrVariable variable) {
    final PsiType varType = variable.getType();
    final PsiElement parent = variable.getParent();

    if (variable instanceof GrParameter && ((GrParameter)variable).getDeclarationScope() instanceof GrMethod ||
        parent instanceof GrForInClause) {
      return;
    }
    else if (parent instanceof GrVariableDeclaration && ((GrVariableDeclaration)parent).isTuple()) {
      //check tuple assignment:  def (int x, int y) = foo()
      final GrVariableDeclaration tuple = (GrVariableDeclaration)parent;
      final GrExpression initializer = tuple.getTupleInitializer();
      if (initializer == null) return;
      if (!(initializer instanceof GrListOrMap) && !PsiUtil.isCompileStatic(variable)) {
        PsiType type = initializer.getType();
        if (type == null) return;
        PsiType valueType = extractIterableTypeParameter(type, false);
        processAssignment(varType, valueType, tuple, variable.getNameIdentifierGroovy());
        return;
      }
    }

    GrExpression initializer = variable.getInitializerGroovy();
    if (initializer == null) return;

    processAssignment(varType, initializer, variable.getNameIdentifierGroovy(), variable);
  }

  @Override
  public void visitListOrMap(@NotNull GrListOrMap listOrMap) {
    super.visitListOrMap(listOrMap); // visitExpression

    Map<String, NamedArgumentDescriptor> descriptors = NamedArgumentUtilKt.getDescriptors(listOrMap);
    if (descriptors.isEmpty()) return;

    GrNamedArgument[] namedArguments = listOrMap.getNamedArguments();
    if (namedArguments.length == 0) return;

    checkNamedArguments(listOrMap, namedArguments, descriptors);
  }

  @Override
  protected void registerError(@NotNull PsiElement location,
                               ProblemHighlightType highlightType,
                               Object... args) {
    registerError(location, (String)args[0], LocalQuickFix.EMPTY_ARRAY, highlightType);
  }
}
