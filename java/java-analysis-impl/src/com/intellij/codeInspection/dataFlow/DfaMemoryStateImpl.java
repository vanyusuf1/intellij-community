// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInsight.Nullability;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.value.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Stack;
import gnu.trove.THashMap;
import gnu.trove.TIntArrayList;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TIntObjectProcedure;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Invariant: qualifiers of the variables used in myEqClasses or myVariableStates must be canonical variables
 * where canonical variable is the minimal DfaVariableValue inside its eqClass, according to EqClass#CANONICAL_VARIABLE_COMPARATOR.
 */
public class DfaMemoryStateImpl implements DfaMemoryState {
  private static final Logger LOG = Logger.getInstance(DfaMemoryStateImpl.class);

  private final DfaValueFactory myFactory;

  private final List<EqClass> myEqClasses;
  // dfa value id -> indices in myEqClasses list of the classes which contain the id
  private final MyIdMap myIdToEqClassesIndices;
  private final Stack<DfaValue> myStack;
  private final DistinctPairSet myDistinctClasses;
  private final LinkedHashMap<DfaVariableValue,DfaVariableState> myVariableStates;
  private final Map<DfaVariableValue,DfaVariableState> myDefaultVariableStates;
  private boolean myEphemeral;

  protected DfaMemoryStateImpl(final DfaValueFactory factory) {
    myFactory = factory;
    myDefaultVariableStates = new THashMap<>();
    myEqClasses = new ArrayList<>();
    myVariableStates = new LinkedHashMap<>();
    myDistinctClasses = new DistinctPairSet(this);
    myStack = new Stack<>();
    myIdToEqClassesIndices = new MyIdMap();
  }

  protected DfaMemoryStateImpl(DfaMemoryStateImpl toCopy) {
    myFactory = toCopy.myFactory;
    myEphemeral = toCopy.myEphemeral;
    myDefaultVariableStates = toCopy.myDefaultVariableStates; // shared between all states

    myStack = new Stack<>(toCopy.myStack);
    myDistinctClasses = new DistinctPairSet(this, toCopy.myDistinctClasses);

    myEqClasses = new ArrayList<>(toCopy.myEqClasses);
    myIdToEqClassesIndices = (MyIdMap)toCopy.myIdToEqClassesIndices.clone();
    myVariableStates = new LinkedHashMap<>(toCopy.myVariableStates);

    myCachedNonTrivialEqClasses = toCopy.myCachedNonTrivialEqClasses;
    myCachedHash = toCopy.myCachedHash;
  }

  @NotNull
  public DfaValueFactory getFactory() {
    return myFactory;
  }

  @NotNull
  @Override
  public DfaMemoryStateImpl createCopy() {
    return new DfaMemoryStateImpl(this);
  }

  @NotNull
  @Override
  public DfaMemoryStateImpl createClosureState() {
    DfaMemoryStateImpl copy = createCopy();
    for (DfaVariableValue value : new ArrayList<>(copy.myVariableStates.keySet())) {
      copy.dropFact(value, DfaFactType.LOCALITY);
    }
    copy.flushFields();
    copy.emptyStack();
    return copy;
  }

  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (!(obj instanceof DfaMemoryStateImpl)) return false;
    DfaMemoryStateImpl that = (DfaMemoryStateImpl)obj;
    if (myCachedHash != null && that.myCachedHash != null && !myCachedHash.equals(that.myCachedHash)) return false;
    return myEphemeral == that.myEphemeral && myStack.equals(that.myStack) &&
           getNonTrivialEqClasses().equals(that.getNonTrivialEqClasses()) &&
           getDistinctClassPairs().equals(that.getDistinctClassPairs()) &&
           myVariableStates.equals(that.myVariableStates);
  }

  Object getSuperficialKey() {
    return Pair.create(myEphemeral, myStack);
  }

  DistinctPairSet getDistinctClassPairs() {
    return myDistinctClasses;
  }

  private LinkedHashSet<EqClass> myCachedNonTrivialEqClasses;
  LinkedHashSet<EqClass> getNonTrivialEqClasses() {
    if (myCachedNonTrivialEqClasses != null) return myCachedNonTrivialEqClasses;

    LinkedHashSet<EqClass> result = new LinkedHashSet<>();
    for (EqClass eqClass : myEqClasses) {
      if (eqClass != null && eqClass.size() > 1) {
        result.add(eqClass);
      }
    }
    return myCachedNonTrivialEqClasses = result;
  }

  private Integer myCachedHash;
  public int hashCode() {
    if (myCachedHash != null) return myCachedHash;

    int hash = ((getNonTrivialEqClasses().hashCode() * 31 +
                 getDistinctClassPairs().hashCode()) * 31 +
                 myStack.hashCode()) * 31 + myVariableStates.hashCode();
    return myCachedHash = hash;
  }

  @SuppressWarnings("HardCodedStringLiteral")
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append('<');
    if (myEphemeral) {
      result.append("ephemeral, ");
    }

    for (EqClass set : getNonTrivialEqClasses()) {
      result.append(set);
    }

    if (!myDistinctClasses.isEmpty()) {
      result.append("\n  distincts: ");
      String distincts = StreamEx.of(getDistinctClassPairs()).map(DistinctPairSet.DistinctPair::toString).sorted().joining(" ");
      result.append(distincts);
    }

    if (!myStack.isEmpty()) {
      result.append("\n  stack: ").append(StringUtil.join(myStack, ","));
    }
    if (!myVariableStates.isEmpty()) {
      result.append("\n  vars: ");
      myVariableStates.forEach((key, value) -> result.append("[").append(key).append("->").append(value).append("] "));
    }
    result.append('>');
    return result.toString();
  }

  @NotNull
  @Override
  public DfaValue pop() {
    myCachedHash = null;
    return myStack.pop();
  }

  @NotNull
  @Override
  public DfaValue peek() {
    return myStack.peek();
  }

  @Nullable
  @Override
  public DfaValue getStackValue(int offset) {
    int index = myStack.size() - 1 - offset;
    return index < 0 ? null : myStack.get(index);
  }

  @Override
  public void push(@NotNull DfaValue value) {
    myCachedHash = null;
    myStack.push(value);
  }

  @Override
  public void emptyStack() {
    while (!myStack.isEmpty() && !(myStack.peek() instanceof DfaControlTransferValue)) {
      myCachedHash = null;
      myStack.pop();
    }
  }

  @Override
  public void setVarValue(DfaVariableValue var, DfaValue value) {
    if (var == value) return;

    value = handleStackValueOnVariableFlush(value, var, null);
    flushVariable(var, var.getInherentNullability() != Nullability.UNKNOWN);
    flushQualifiedMethods(var);

    if (value instanceof DfaUnknownValue) {
      setVariableState(var, getVariableState(var).withNotNull());
      return;
    }

    DfaVariableState state = getVariableState(var).withValue(value);
    if (value instanceof DfaFactMapValue) {
      DfaFactMap facts = filterFactsOnAssignment(var, ((DfaFactMapValue)value).getFacts());
      setVariableState(var, state.withFacts(facts));
      SpecialFieldValue specialFieldValue = facts.get(DfaFactType.SPECIAL_FIELD_VALUE);
      if (specialFieldValue != null) {
        DfaValue targetSpecialField = specialFieldValue.getField().createValue(myFactory, var);
        if (targetSpecialField instanceof DfaVariableValue) {
          setVarValue((DfaVariableValue)targetSpecialField, specialFieldValue.getValue());
        }
      }
    }
    else {
      setVariableState(var, isNull(value) ? state.withFact(DfaFactType.NULLABILITY, DfaNullability.NULL) : state);
      DfaRelation dfaEqual = DfaRelation.createRelation(var, RelationType.EQ, value);
      if (dfaEqual == null) return;
      applyCondition(dfaEqual);

      if (value instanceof DfaVariableValue) {
        DfaVariableState targetState = getVariableState((DfaVariableValue)value);
        setVariableState(var, targetState.withFacts(filterFactsOnAssignment(var, targetState.myFactMap)));
      }
    }

    updateEqClassesByState(var);
  }

  protected DfaFactMap filterFactsOnAssignment(DfaVariableValue var, @NotNull DfaFactMap facts) {
    return facts;
  }

  private DfaValue handleStackValueOnVariableFlush(DfaValue value,
                                                   DfaVariableValue flushed,
                                                   DfaVariableValue replacement) {
    if (value.dependsOn(flushed)) {
      if (value instanceof DfaVariableValue) {
        if (replacement != null) {
          DfaVariableValue target = replaceQualifier((DfaVariableValue)value, flushed, replacement);
          if (target != value) return target;
        }
        if (value.getType() instanceof PsiPrimitiveType) {
          return myFactory.getFactValue(DfaFactType.RANGE, getValueFact(value, DfaFactType.RANGE));
        }
        DfaNullability dfaNullability = isNotNull(value) ? DfaNullability.NOT_NULL : getValueFact(value, DfaFactType.NULLABILITY);
        if (dfaNullability == null) {
          dfaNullability = DfaNullability.fromNullability(((DfaVariableValue)value).getInherentNullability());
        }
        return myFactory.withFact(myFactory.createTypeValue(value.getType(), Nullability.UNKNOWN), DfaFactType.NULLABILITY, dfaNullability);
      }
      return myFactory.getFactValue(DfaFactType.RANGE, getValueFact(value, DfaFactType.RANGE));
    }
    return value;
  }

  @Nullable("for non-variables and non-constants which can't be compared by ==")
  private Integer getOrCreateEqClassIndex(@NotNull DfaValue dfaValue) {
    int i = getEqClassIndex(dfaValue);
    if (i != -1) return i;
    if (!canBeInRelation(dfaValue)) return null;
    dfaValue = canonicalize(dfaValue);
    EqClass eqClass = new EqClass(myFactory);
    eqClass.add(dfaValue.getID());

    int resultIndex = storeClass(eqClass);
    checkInvariants();

    return resultIndex;
  }

  private int storeClass(EqClass eqClass) {
    int freeIndex = myEqClasses.indexOf(null);
    int resultIndex = freeIndex >= 0 ? freeIndex : myEqClasses.size();
    if (freeIndex >= 0) {
      myEqClasses.set(freeIndex, eqClass);
    }
    else {
      myEqClasses.add(eqClass);
    }
    eqClass.forEach(id -> {
      myIdToEqClassesIndices.put(id, resultIndex);
      return true;
    });
    return resultIndex;
  }

  /**
   * Returns true if current state describes all possible concrete program states described by {@code that} state.
   *
   * @param that a sub-state candidate
   * @return true if current state is a super-state of the supplied state.
   */
  public boolean isSuperStateOf(DfaMemoryStateImpl that) {
    if (myEphemeral && !that.myEphemeral) return false;
    if (myStack.size() != that.myStack.size()) return false;
    for (int i = 0; i < myStack.size(); i++) {
      if (!isSuperValue(myStack.get(i), that.myStack.get(i))) return false;
    }
    int[] thisToThat = getClassesMap(that);
    if (thisToThat == null) return false;
    for (DistinctPairSet.DistinctPair pair : myDistinctClasses) {
      int firstIndex = thisToThat[pair.getFirstIndex()];
      int secondIndex = thisToThat[pair.getSecondIndex()];
      if (firstIndex == -1 || secondIndex == -1 || firstIndex == secondIndex) return false;
      RelationType relation = that.myDistinctClasses.getRelation(firstIndex, secondIndex);
      if (relation == null || pair.isOrdered() && relation != RelationType.LT) return false;
    }
    Set<DfaVariableValue> values = new HashSet<>(this.myVariableStates.keySet());
    values.addAll(that.myVariableStates.keySet());
    for (DfaVariableValue value : values) {
      // the default variable state is not always a superstate for any non-default state
      // (e.g. default can be nullable, but current state can be notnull)
      // so we cannot limit checking to myVariableStates map only
      DfaVariableState thisState = this.getVariableState(value);
      DfaVariableState thatState = that.getVariableState(value);
      if(!thisState.isSuperStateOf(thatState)) return false;
    }
    return true;
  }

  /**
   * Returns an int array which maps this state class indices to that state class indices.
   *
   * @param that other state to map class indices
   * @return an int array which values are indices of the corresponding that state class which contains
   * all the values from this state class or -1 if there's no corresponding that state class.
   * Null is returned if at least one of this state classes contains values which do not belong to the same
   * class in that state
   */
  @Nullable
  private int[] getClassesMap(DfaMemoryStateImpl that) {
    List<EqClass> thisClasses = this.myEqClasses;
    List<EqClass> thatClasses = that.myEqClasses;
    int thisSize = thisClasses.size();
    int thatSize = thatClasses.size();
    int[] thisToThat = new int[thisSize];
    // If any two values are equivalent in this, they also must be equivalent in that
    for (int thisIdx = 0; thisIdx < thisSize; thisIdx++) {
      EqClass thisClass = thisClasses.get(thisIdx);
      thisToThat[thisIdx] = -1;
      if (thisClass != null) {
        boolean found = false;
        for (int thatIdx = 0; thatIdx < thatSize; thatIdx++) {
          EqClass thatClass = thatClasses.get(thatIdx);
          if (thatClass != null && thatClass.containsAll(thisClass)) {
            thisToThat[thisIdx] = thatIdx;
            found = true;
            break;
          }
        }
        if (!found && thisClass.size() > 1) return null;
      }
    }
    return thisToThat;
  }

  @Override
  public boolean shouldCompareByEquals(DfaValue dfaLeft, DfaValue dfaRight) {
    if (dfaLeft == dfaRight && !(dfaLeft instanceof DfaBoxedValue) && !(dfaLeft instanceof DfaConstValue)) {
      return false;
    }
    return !isNull(dfaLeft) && !isNull(dfaRight) &&
           DfaUtil.isComparedByEquals(getPsiType(dfaLeft)) && DfaUtil.isComparedByEquals(getPsiType(dfaRight));
  }

  private static boolean isSuperValue(DfaValue superValue, DfaValue subValue) {
    if (superValue == DfaUnknownValue.getInstance() || superValue == subValue) return true;
    if (superValue instanceof DfaFactMapValue && subValue instanceof DfaFactMapValue) {
      return ((DfaFactMapValue)superValue).getFacts().isSuperStateOf(((DfaFactMapValue)subValue).getFacts());
    }
    return false;
  }

  private static boolean canBeInRelation(@NotNull DfaValue value) {
    return value instanceof DfaBoxedValue || value instanceof DfaVariableValue || value instanceof DfaConstValue;
  }

  private boolean canBeNaN(@NotNull DfaValue dfaValue) {
    if (!(dfaValue instanceof DfaVariableValue) ||
        (!PsiType.FLOAT.equals(dfaValue.getType()) && !PsiType.DOUBLE.equals(dfaValue.getType()))) {
      return false;
    }
    EqClass eqClass = getEqClass(dfaValue);
    DfaConstValue constant = eqClass == null ? null : eqClass.findConstant();
    return constant == null || isNaN(constant);
  }


  private boolean isEffectivelyNaN(@NotNull DfaValue dfaValue) {
    EqClass eqClass = getEqClass(dfaValue);
    return eqClass != null && isNaN(eqClass.findConstant());
  }

  List<EqClass> getEqClasses() {
    return myEqClasses;
  }

  @Nullable
  private EqClass getEqClass(DfaValue value) {
    int index = getEqClassIndex(value);
    return index == -1 ? null : myEqClasses.get(index);
  }

  /**
   * Returns existing equivalence class index or -1 if not found
   * @param dfaValue value to find a class for
   * @return class index or -1 if not found
   */
  int getEqClassIndex(@NotNull DfaValue dfaValue) {
    Integer classIndex = myIdToEqClassesIndices.get(dfaValue.getID());
    if (classIndex == null) {
      dfaValue = canonicalize(dfaValue);
      classIndex = myIdToEqClassesIndices.get(dfaValue.getID());
    }

    if (classIndex == null) return -1;

    EqClass aClass = myEqClasses.get(classIndex);
    assert aClass.contains(dfaValue.getID());
    return classIndex;
  }

  DfaVariableValue getCanonicalVariable(DfaValue val) {
    EqClass eqClass = getEqClass(val);
    return eqClass == null ? null : eqClass.getCanonicalVariable();
  }

  /**
   * Unite equivalence classes containing given values
   *
   * @param val1 the first value
   * @param val2 the second value
   * @return true if classes were successfully united.
   */
  private boolean uniteClasses(DfaValue val1, DfaValue val2) {
    DfaVariableValue var1 = getCanonicalVariable(val1);
    DfaVariableValue var2 = getCanonicalVariable(val2);
    Integer c1Index = getOrCreateEqClassIndex(val1);
    Integer c2Index = getOrCreateEqClassIndex(val2);
    if (c1Index == null || c2Index == null || c1Index.equals(c2Index)) return true;

    if (!myDistinctClasses.unite(c1Index, c2Index)) return false;

    EqClass c1 = myEqClasses.get(c1Index);
    EqClass c2 = myEqClasses.get(c2Index);

    if (c1.findConstant() != null && c2.findConstant() != null) return false;

    EqClass newClass = new EqClass(c1);

    myEqClasses.set(c1Index, newClass);
    for (int i = 0; i < c2.size(); i++) {
      int c = c2.get(i);
      newClass.add(c);
      myIdToEqClassesIndices.put(c, c1Index);
    }

    myEqClasses.set(c2Index, null);
    checkInvariants();

    if (var1 == null || var2 == null || var1 == var2) return true;
    int compare = EqClass.CANONICAL_VARIABLE_COMPARATOR.compare(var1, var2);
    return compare < 0 ? convertQualifiers(var2, var1) : convertQualifiers(var1, var2);
  }

  private static DfaVariableValue replaceQualifier(DfaVariableValue variable, DfaVariableValue from, DfaVariableValue to) {
    DfaVariableValue qualifier = variable.getQualifier();
    if (qualifier != null) {
      return variable.withQualifier(replaceQualifier(qualifier == from ? to : qualifier, from, to));
    }
    return variable;
  }

  private boolean convertQualifiers(DfaVariableValue from, DfaVariableValue to) {
    assert from != to;
    if (from.getDependentVariables().isEmpty()) return true;
    List<DfaVariableValue> vars = new ArrayList<>(myVariableStates.keySet());
    for (DfaVariableValue var : vars) {
      DfaVariableValue target = replaceQualifier(var, from, to);
      if (target != var) {
        DfaVariableState fromState = myVariableStates.remove(var);
        if (fromState != null) {
          DfaVariableState toState = myVariableStates.get(target);
          if (toState == null) {
            toState = fromState;
          }
          else {
            toState = fromState.intersectMap(toState.myFactMap);
            if (toState == null) return false;
          }
          setVariableState(target, toState);
        }
      }
    }
    for (int valueId : myIdToEqClassesIndices.keys()) {
      DfaValue value = myFactory.getValue(valueId);
      DfaVariableValue var = ObjectUtils.tryCast(value, DfaVariableValue.class);
      if (var == null || var.getQualifier() != from) continue;
      DfaVariableValue target = var.withQualifier(to);
      if (!uniteClasses(var, target)) return false;
      removeEquivalenceForVariableAndWrappers(var);
    }
    return true;
  }

  private void checkInvariants() {
    if (!LOG.isDebugEnabled() && !ApplicationManager.getApplication().isEAP()) return;
    myIdToEqClassesIndices.forEachEntry((id, classIndex) -> {
      EqClass eqClass = myEqClasses.get(classIndex);
      if (eqClass == null || !eqClass.contains(id)) {
        LOG.error("Invariant violated: null-class for id=" + myFactory.getValue(id));
      }
      return true;
    });
    myDistinctClasses.forEach(DistinctPairSet.DistinctPair::check);
  }

  @Override
  public boolean isNull(DfaValue dfaValue) {
    if (dfaValue instanceof DfaConstValue) return ((DfaConstValue)dfaValue).getValue() == null;

    if (dfaValue instanceof DfaVariableValue) {
      int c1Index = getEqClassIndex(dfaValue);
      return c1Index >= 0 && c1Index == getEqClassIndex(myFactory.getConstFactory().getNull());
    }

    return false;
  }

  @Override
  public boolean isNotNull(DfaValue dfaVar) {
    if (dfaVar instanceof DfaConstValue) return ((DfaConstValue)dfaVar).getValue() != null;
    if (dfaVar instanceof DfaBoxedValue) return true;
    if (dfaVar instanceof DfaFactMapValue) return DfaNullability.isNotNull(((DfaFactMapValue)dfaVar).getFacts());
    if (dfaVar instanceof DfaVariableValue) {
      if (getVariableState((DfaVariableValue)dfaVar).isNotNull()) return true;

      DfaConstValue constantValue = getConstantValue(dfaVar);
      if (constantValue != null && constantValue.getValue() != null) return true;
    }

    DfaConstValue dfaNull = myFactory.getConstFactory().getNull();
    Integer c1Index = getOrCreateEqClassIndex(dfaVar);
    int c2Index = getEqClassIndex(dfaNull);
    if (c1Index == null || c2Index < 0) {
      return false;
    }

    return myDistinctClasses.areDistinctUnordered(c1Index, c2Index);
  }

  /**
   * Returns constants which are known to be not equal to given value
   *
   * @param value a value to test
   * @return set of non-equal constant values
   */
  public Set<Object> getNonEqualConstants(DfaVariableValue value) {
    int index = getEqClassIndex(value);
    if (index == -1 || myEqClasses.get(index).findConstant() != null) return Collections.emptySet();
    return StreamEx.of(getDistinctClassPairs())
      .map(pair -> pair.getOtherClass(index))
      .nonNull()
      .map(EqClass::findConstant)
      .nonNull()
      .map(DfaConstValue::getValue)
      .toSet();
  }

  @Override
  @Nullable
  @Contract("null -> null")
  public DfaConstValue getConstantValue(@Nullable DfaValue value) {
    return getConstantValue(value, true);
  }

  private DfaConstValue getConstantValue(@Nullable DfaValue value, boolean unbox) {
    if (value instanceof DfaConstValue) {
      return (DfaConstValue)value;
    }
    if (value instanceof DfaVariableValue) {
      if (unbox && TypeConversionUtil.isPrimitiveWrapper(value.getType())) {
        value = SpecialField.UNBOX.createValue(myFactory, value);
      }
      EqClass ec = getEqClass(value);
      DfaConstValue constValue = ec == null ? null : ec.findConstant();
      if (constValue != null) return constValue;
      DfaVariableState state = getExistingVariableState((DfaVariableValue)value);
      LongRangeSet range = state != null ? state.getFact(DfaFactType.RANGE) : null;
      Long constantValue = range == null ? null : range.getConstantValue();
      if (constantValue != null) {
        return myFactory.getConstFactory().createFromValue(constantValue, PsiType.LONG);
      }
    }
    return null;
  }

  @Override
  public void markEphemeral() {
    myEphemeral = true;
  }

  @Override
  public boolean isEphemeral() {
    return myEphemeral;
  }

  @Override
  public boolean isEmptyStack() {
    return myStack.isEmpty();
  }

  @Override
  public boolean castTopOfStack(@NotNull DfaPsiType type) {
    DfaValue value = peek();

    DfaFactMap facts = null;
    if (value instanceof DfaVariableValue) {
      DfaVariableValue dfaVar = (DfaVariableValue)value;

      if (isNull(dfaVar)) return true;
      DfaVariableState newState = getVariableState(dfaVar).withInstanceofValue(type);
      if (newState == null) return false;
      setVariableState(dfaVar, newState);
    } else if (value instanceof DfaFactMapValue) {
      facts = ((DfaFactMapValue)value).getFacts();
    }
    if (facts != null) {
      DfaFactMap newFacts = TypeConstraint.withInstanceOf(facts, type);
      if (newFacts == null) return false;
      pop();
      push(myFactory.getFactFactory().createValue(newFacts));
    }
    return true;
  }

  private boolean applyFacts(DfaValue value, DfaFactMap facts) {
    if (value instanceof DfaVariableValue) {
      DfaVariableState oldState = getVariableState((DfaVariableValue)value);
      DfaVariableState newState = oldState.intersectMap(facts);
      if (newState == null) {
        newState = oldState.withoutFact(DfaFactType.TYPE_CONSTRAINT);
        if (newState.intersectMap(facts) != null && DfaNullability.isNotNull(facts)) {
          setVariableState((DfaVariableValue)value, newState);
          return applyRelation(value, getFactory().getConstFactory().getNull(), false);
        }
        return false;
      }
      setVariableState((DfaVariableValue)value, newState);
      if (DfaUtil.isComparedByEquals(newState.getTypeConstraint().getPsiType()) &&
          !newState.getTypeConstraint().equals(oldState.getTypeConstraint())) {
        // Type is narrowed to java.lang.String, java.lang.Integer, etc.: we consider String & boxed types
        // equivalence by content, but other object types by reference, so we need to remove distinct pairs, if any.
        convertReferenceEqualityToValueEquality(value);
      }
      updateEquivalentVariables((DfaVariableValue)value, newState);
      return updateEqClassesByState((DfaVariableValue)value);
    }
    return true;
  }

  private void convertReferenceEqualityToValueEquality(DfaValue value) {
    int id = canonicalize(value).getID();
    Integer index = myIdToEqClassesIndices.get(id);
    assert index != null;
    for (Iterator<DistinctPairSet.DistinctPair> iterator = myDistinctClasses.iterator(); iterator.hasNext(); ) {
      DistinctPairSet.DistinctPair pair = iterator.next();
      EqClass otherClass = pair.getOtherClass(index);
      if (otherClass != null && otherClass.findConstant() != getFactory().getConstFactory().getNull()) {
        iterator.remove();
      }
    }
  }

  private boolean updateEqClassesByState(DfaVariableValue value) {
    if (DfaNullability.isNotNull(getVariableState(value).myFactMap)) {
      return applyRelation(value, getFactory().getConstFactory().getNull(), true);
    }
    return true;
  }

  @Override
  public void dropFact(@NotNull DfaValue value, @NotNull DfaFactType<?> factType) {
    if (value instanceof DfaVariableValue) {
      DfaVariableValue var = (DfaVariableValue)value;
      DfaVariableState state = getExistingVariableState(var);
      if (state != null) {
        state = state.withoutFact(factType);
        setVariableState(var, state);
      }
    }
  }

  @Override
  public <T> boolean applyFact(@NotNull DfaValue value, @NotNull DfaFactType<T> factType, @Nullable T factValue) {
    if (value instanceof DfaFactMapValue) {
      return ((DfaFactMapValue)value).getFacts().intersect(factType, factValue) != null;
    }
    if (value instanceof DfaBinOpValue && factType == DfaFactType.RANGE && factValue != null) {
      return propagateRangeBack((LongRangeSet)factValue, (DfaBinOpValue)value);
    }
    if (value instanceof DfaVariableValue) {
      DfaVariableValue var = (DfaVariableValue)value;
      if (factValue != null) {
        DfaVariableState state = getVariableState(var);
        DfaVariableState newState = state.intersectFact(factType, factValue);
        if (newState == null) return false;
        setVariableState(var, newState);
        updateEquivalentVariables(var, newState);
        return updateEqClassesByState(var);
      }
    }
    return true;
  }

  private boolean propagateRangeBack(@NotNull LongRangeSet factValue, @NotNull DfaBinOpValue binOp) {
    boolean isLong = PsiType.LONG.equals(binOp.getType());
    LongRangeSet appliedRange = isLong ? factValue : factValue.intersect(Objects.requireNonNull(LongRangeSet.fromType(PsiType.INT)));
    DfaVariableValue left = binOp.getLeft();
    DfaValue right = binOp.getRight();
    LongRangeSet leftRange = getValueFact(left, DfaFactType.RANGE);
    LongRangeSet rightRange = getValueFact(right, DfaFactType.RANGE);
    if (leftRange == null || rightRange == null) return true;
    LongRangeSet result = getBinOpRange(binOp);
    assert result != null;
    if (!result.intersects(appliedRange)) return false;
    LongRangeSet leftConstraint = LongRangeSet.all();
    LongRangeSet rightConstraint = LongRangeSet.all();
    switch (binOp.getOperation()) {
      case PLUS:
        leftConstraint = appliedRange.minus(rightRange, isLong);
        rightConstraint = appliedRange.minus(leftRange, isLong);
        break;
      case MINUS:
        leftConstraint = rightRange.plus(appliedRange, isLong);
        rightConstraint = leftRange.minus(appliedRange, isLong);
        break;
      case REM:
        Long value = rightRange.getConstantValue();
        if (value != null) {
          leftConstraint = LongRangeSet.fromRemainder(value, appliedRange.intersect(result));
        }
        break;
    }
    return applyFact(left, DfaFactType.RANGE, leftConstraint) && applyFact(right, DfaFactType.RANGE, rightConstraint);
  }

  @Override
  public boolean applyContractCondition(DfaCondition condition) {
    if (condition instanceof DfaRelation) {
      DfaRelation relation = (DfaRelation)condition;
      if (relation.isEquality() &&
          DfaConstValue.isConstant(relation.getRightOperand(), null) &&
          (relation.getLeftOperand() instanceof DfaUnknownValue ||
           (relation.getLeftOperand() instanceof DfaVariableValue &&
            getVariableState((DfaVariableValue)relation.getLeftOperand()).getNullability() == Nullability.UNKNOWN))) {
        markEphemeral();
      }
    }
    return applyCondition(condition);
  }

  @Override
  public boolean areEqual(@NotNull DfaValue value1, @NotNull DfaValue value2) {
    if (value1 instanceof DfaBinOpValue && value2 instanceof DfaBinOpValue) {
      DfaBinOpValue binOp1 = (DfaBinOpValue)value1;
      DfaBinOpValue binOp2 = (DfaBinOpValue)value2;
      return binOp1.getOperation() == binOp2.getOperation() &&
             areEqual(binOp1.getLeft(), binOp2.getLeft()) &&
             areEqual(binOp1.getRight(), binOp2.getRight());
    }
    if (!(value1 instanceof DfaConstValue) && !(value1 instanceof DfaVariableValue)) return false;
    if (!(value2 instanceof DfaConstValue) && !(value2 instanceof DfaVariableValue)) return false;
    if (value1 == value2) return true;
    DfaConstValue const1 = getConstantValue(value1, false);
    if (const1 != null && const1 == getConstantValue(value2, false)) return true;
    int index1 = getEqClassIndex(value1);
    int index2 = getEqClassIndex(value2);
    return index1 != -1 && index1 == index2;
  }

  @Nullable
  @Override
  public RelationType getRelation(DfaValue left, DfaValue right) {
    int leftClass = getEqClassIndex(left);
    int rightClass = getEqClassIndex(right);
    if (leftClass == -1 || rightClass == -1) return null;
    if (leftClass == rightClass) return RelationType.EQ;
    return myDistinctClasses.getRelation(leftClass, rightClass);
  }

  @Override
  public boolean applyCondition(DfaCondition dfaCond) {
    if (!(dfaCond instanceof DfaRelation)) {
      return dfaCond != DfaCondition.getFalse();
    }
    return applyRelationCondition((DfaRelation)dfaCond);
  }

  private boolean applyRelationCondition(@NotNull DfaRelation dfaRelation) {
    DfaValue dfaLeft = dfaRelation.getLeftOperand();
    DfaValue dfaRight = dfaRelation.getRightOperand();
    RelationType relationType = dfaRelation.getRelation();

    DfaConstValue sentinel = getFactory().getConstFactory().getSentinel();
    if (dfaLeft == sentinel || dfaRight == sentinel) {
      assert relationType == RelationType.EQ || relationType == RelationType.NE;
      return (dfaLeft == dfaRight) == (relationType == RelationType.EQ);
    }
    if (dfaLeft instanceof DfaUnknownValue || dfaRight instanceof DfaUnknownValue) return true;

    LongRangeSet left = getValueFact(dfaLeft, DfaFactType.RANGE);
    LongRangeSet right = getValueFact(dfaRight, DfaFactType.RANGE);

    if (left != null && right != null) {
      if (!applyFact(dfaLeft, DfaFactType.RANGE, right.fromRelation(relationType)) ||
          !applyFact(dfaRight, DfaFactType.RANGE, left.fromRelation(relationType.getFlipped()))) {
        return false;
      }
      if (!applyBinOpRelations(dfaLeft, relationType, dfaRight)) return false;
    }

    if (dfaRight instanceof DfaFactMapValue) {
      DfaFactMapValue factValue = (DfaFactMapValue)dfaRight;
      if ((relationType == RelationType.IS || relationType == RelationType.EQ) &&
          DfaNullability.isNotNull(factValue.getFacts()) &&
          !applyRelation(dfaLeft, getFactory().getConstFactory().getNull(), true)) {
        return false;
      }
      if ((relationType == RelationType.EQ || relationType.isInequality()) &&
          !applyUnboxedRelation(dfaLeft, dfaRight, relationType.isInequality())) {
        return false;
      }
      if (dfaLeft instanceof DfaVariableValue) {
        DfaVariableValue dfaVar = (DfaVariableValue)dfaLeft;

        switch (relationType) {
          case IS:
            return applyFacts(dfaVar, factValue.getFacts());
          case IS_NOT: {
            boolean isNotNull = DfaNullability.isNotNull(factValue.getFacts());
            TypeConstraint constraint = factValue.get(DfaFactType.TYPE_CONSTRAINT);
            if (constraint != null && constraint.getNotInstanceofValues().isEmpty()) {
              DfaVariableState state = getVariableState(dfaVar);
              for (DfaPsiType type : constraint.getInstanceofValues()) {
                state = state.withNotInstanceofValue(type);
                if (state == null) {
                  return isNotNull && !getVariableState(dfaVar).isNotNull() &&
                         applyRelation(dfaVar, myFactory.getConstFactory().getNull(), false);
                }
                setVariableState(dfaVar, state);
              }
            }
            return true;
          }
          default:
            return true;
        }
      }
      if (relationType == RelationType.IS || relationType == RelationType.EQ) {
        return getFactMap(dfaLeft).intersect(factValue.getFacts()) != null;
      }
      return true;
    }

    if (isEffectivelyNaN(dfaLeft) || isEffectivelyNaN(dfaRight)) {
      applyEquivalenceRelation(relationType, dfaLeft, dfaRight);
      return relationType == RelationType.NE;
    }
    if ((canBeNaN(dfaLeft) && !isNull(dfaRight)) || (canBeNaN(dfaRight) && !isNull(dfaLeft))) {
      if (dfaLeft == dfaRight && dfaLeft instanceof DfaVariableValue && !(dfaLeft.getType() instanceof PsiPrimitiveType)) {
        return !dfaRelation.isNonEquality();
      }

      applyEquivalenceRelation(relationType, dfaLeft, dfaRight);
      return true;
    }

    return applyEquivalenceRelation(relationType, dfaLeft, dfaRight);
  }

  private boolean applyBinOpRelations(DfaValue left, RelationType type, DfaValue right) {
    if (type != RelationType.LT && type != RelationType.GT && type != RelationType.NE && type != RelationType.EQ) return true;
    if (left instanceof DfaBinOpValue) {
      DfaBinOpValue sum = (DfaBinOpValue)left;
      DfaBinOpValue.BinOp op = sum.getOperation();
      if (op != DfaBinOpValue.BinOp.PLUS && op != DfaBinOpValue.BinOp.MINUS) return true;
      LongRangeSet leftRange = getValueFact(sum.getLeft(), DfaFactType.RANGE);
      LongRangeSet rightRange = getValueFact(sum.getRight(), DfaFactType.RANGE);
      if (leftRange == null || rightRange == null) return true;
      boolean isLong = PsiType.LONG.equals(sum.getType());
      LongRangeSet rightNegated = rightRange.negate(isLong);
      LongRangeSet rightCorrected = op == DfaBinOpValue.BinOp.MINUS ? rightNegated : rightRange;

      LongRangeSet resultRange = getValueFact(right, DfaFactType.RANGE);
      RelationType correctedRelation = correctRelation(type, leftRange, rightCorrected, resultRange, isLong);
      if (op == DfaBinOpValue.BinOp.MINUS) {
        if (resultRange != null) {
          long min = resultRange.min();
          long max = resultRange.max();
          if (min == 0 && max == 0) {
            // a-b (rel) 0 => a (rel) b
            if (!applyCondition(sum.getLeft().cond(correctedRelation, sum.getRight()))) return false;
          }
          else if (min >= 0 && RelationType.GE.isSubRelation(type)) {
            RelationType correctedGt = correctRelation(RelationType.GT, leftRange, rightCorrected, resultRange, isLong);
            if (!applyCondition(sum.getLeft().cond(correctedGt, sum.getRight()))) return false;
          }
          else if (max <= 0 && RelationType.LE.isSubRelation(type)) {
            RelationType correctedLt = correctRelation(RelationType.LT, leftRange, rightCorrected, resultRange, isLong);
            if (!applyCondition(sum.getLeft().cond(correctedLt, sum.getRight()))) return false;
          }
          if (RelationType.EQ.equals(type) && !resultRange.contains(0)) {
            // a-b == non-zero => a != b
            if (!applyRelation(sum.getLeft(), sum.getRight(), true)) return false;
          }
        }
      }
      if (op == DfaBinOpValue.BinOp.PLUS && RelationType.EQ == type && resultRange != null &&
          !resultRange.intersects(LongRangeSet.all().mul(LongRangeSet.point(2), true))) {
        // a+b == odd => a != b
        if (!applyRelation(sum.getLeft(), sum.getRight(), true)) return false;
      }
      if (right instanceof DfaVariableValue) {
        // a+b (rel) c && a == c => b (rel) 0
        if (areEqual(sum.getLeft(), right)) {
          RelationType finalRelation = op == DfaBinOpValue.BinOp.MINUS ?
                                       Objects.requireNonNull(correctedRelation.getFlipped()) : correctedRelation;
          if (!applyCondition(sum.getRight().cond(finalRelation, myFactory.getInt(0)))) return false;
        }
        // a+b (rel) c && b == c => a (rel) 0
        if (op == DfaBinOpValue.BinOp.PLUS && areEqual(sum.getRight(), right)) {
          if (!applyCondition(sum.getLeft().cond(correctedRelation, myFactory.getInt(0)))) return false;
        }

        if (!leftRange.subtractionMayOverflow(op == DfaBinOpValue.BinOp.MINUS ? rightRange : rightNegated, isLong)) {
          // a-positiveNumber >= b => a > b
          if (rightCorrected.max() < 0 && RelationType.GE.isSubRelation(type)) {
            if (!applyLessThanRelation(right, sum.getLeft())) return false;
          }
          // a+positiveNumber >= b => a > b
          if (rightCorrected.min() > 0 && RelationType.LE.isSubRelation(type)) {
            if (!applyLessThanRelation(sum.getLeft(), right)) return false;
          }
        }
        if (RelationType.EQ == type && !rightRange.contains(0)) {
          // a+nonZero == b => a != b
          if (!applyRelation(sum.getLeft(), right, true)) return false;
        }
      }
    }
    return true;
  }

  private static RelationType correctRelation(RelationType relation, LongRangeSet summand1, LongRangeSet summand2,
                                              LongRangeSet resultRange, boolean isLong) {
    if (relation != RelationType.LT && relation != RelationType.GT) return relation;
    boolean overflowPossible = true;
    if (!isLong) {
      LongRangeSet overflowRange = getIntegerSumOverflowValues(summand1, summand2);
      overflowPossible = !overflowRange.isEmpty() && (resultRange == null || resultRange.fromRelation(relation).intersects(overflowRange));
    }
    return overflowPossible ? RelationType.NE : relation;
  }

  @NotNull
  private static LongRangeSet getIntegerSumOverflowValues(LongRangeSet left, LongRangeSet right) {
    if (left.isEmpty() || right.isEmpty()) return LongRangeSet.empty();
    long sumMin = left.min() + right.min();
    long sumMax = left.max() + right.max();
    LongRangeSet result = LongRangeSet.empty();
    if (sumMin < Integer.MIN_VALUE) {
      result = result.unite(LongRangeSet.range((int)sumMin, Integer.MAX_VALUE));
    }
    if (sumMax > Integer.MAX_VALUE) {
      result = result.unite(LongRangeSet.range(Integer.MIN_VALUE, (int)sumMax));
    }
    return result;
  }

  private void updateVarStateOnComparison(@NotNull DfaVariableValue dfaVar, DfaValue value, boolean isNegated) {
    if (isNegated) {
      if (isNull(value)) {
        setVariableState(dfaVar, getVariableState(dfaVar).withFact(DfaFactType.NULLABILITY, DfaNullability.NOT_NULL));
      }
    } else {
      if (value instanceof DfaConstValue) {
        Object constValue = ((DfaConstValue)value).getValue();
        if (constValue == null) {
          setVariableState(dfaVar, getVariableState(dfaVar).withFact(DfaFactType.NULLABILITY, DfaNullability.NULL));
          return;
        }
        DfaPsiType dfaType = myFactory.createDfaType(((DfaConstValue)value).getType());
        DfaVariableState state = getVariableState(dfaVar).withInstanceofValue(dfaType);
        if (state != null) {
          setVariableState(dfaVar, state);
        }
      }
      if (isNotNull(value) && !isNotNull(dfaVar)) {
        setVariableState(dfaVar, getVariableState(dfaVar).withoutFact(DfaFactType.NULLABILITY));
        applyRelation(dfaVar, myFactory.getConstFactory().getNull(), true);
      }
    }
  }

  private boolean applyEquivalenceRelation(RelationType type, DfaValue dfaLeft, DfaValue dfaRight) {
    boolean isNegated = type == RelationType.NE || type == RelationType.GT || type == RelationType.LT;
    if (!isNegated && type != RelationType.EQ) {
      return true;
    }
    if (dfaLeft instanceof DfaVariableValue && dfaRight instanceof DfaConstValue) {
      LongRangeSet leftRange = getValueFact(dfaLeft, DfaFactType.RANGE);
      LongRangeSet rightRange = getValueFact(dfaRight, DfaFactType.RANGE);
      if (leftRange != null && rightRange != null) {
        LongRangeSet appliedRange = rightRange.fromRelation(type);
        if (appliedRange != null) {
          if (!applyRangeToRelatedValues(dfaLeft, appliedRange)) return false;
          if (appliedRange.contains(leftRange)) return true;
        }
      }
    }

    if (dfaLeft == dfaRight) {
      return !isNegated || (dfaLeft instanceof DfaVariableValue && ((DfaVariableValue)dfaLeft).containsCalls());
    }

    if (dfaLeft instanceof DfaVariableValue && (dfaRight instanceof DfaVariableValue || dfaRight instanceof DfaConstValue) &&
        (type == RelationType.NE || type == RelationType.EQ)) {
      DfaConstValue leftConstant = getConstantValue(dfaLeft, false);
      DfaConstValue rightConstant = getConstantValue(dfaRight, false);
      if (leftConstant != null && leftConstant.getValue() instanceof PsiType && rightConstant == null) {
        assert dfaRight instanceof DfaVariableValue; // otherwise rightConstant is not-null
        ThreeState result = processGetClass((DfaVariableValue)dfaRight, (PsiType)leftConstant.getValue(), isNegated);
        if (result != ThreeState.UNSURE) return result.toBoolean();
      }
      if (rightConstant != null && rightConstant.getValue() instanceof PsiType && leftConstant == null) {
        ThreeState result = processGetClass((DfaVariableValue)dfaLeft, (PsiType)rightConstant.getValue(), isNegated);
        if (result != ThreeState.UNSURE) return result.toBoolean();
      }
      if (dfaRight instanceof DfaVariableValue && !isNegated) {
        if (!equalizeTypesOnGetClass((DfaVariableValue)dfaLeft, (DfaVariableValue)dfaRight)) {
          return false;
        }
      }
    }

    if (isNull(dfaLeft) && isNotNull(dfaRight) || isNull(dfaRight) && isNotNull(dfaLeft)) {
      return isNegated;
    }

    if (dfaLeft instanceof DfaVariableValue) {
      updateVarStateOnComparison((DfaVariableValue)dfaLeft, dfaRight, isNegated);
    }
    if (dfaRight instanceof DfaVariableValue) {
      updateVarStateOnComparison((DfaVariableValue)dfaRight, dfaLeft, isNegated);
    }

    if (type == RelationType.LT) {
      if (!applyLessThanRelation(dfaLeft, dfaRight)) return false;
    } else if (type == RelationType.GT) {
      if (!applyLessThanRelation(dfaRight, dfaLeft)) return false;
    } else {
      if (!isNegated && !applySpecialFieldEquivalence(dfaLeft, dfaRight)) {
        return false;
      }
      if (!applyRelation(dfaLeft, dfaRight, isNegated)) return false;
    }
    if (!checkCompareWithBooleanLiteral(dfaLeft, dfaRight, isNegated)) {
      return false;
    }
    return applyUnboxedRelation(dfaLeft, dfaRight, isNegated);
  }

  private boolean equalizeTypesOnGetClass(DfaVariableValue dfaLeft, DfaVariableValue dfaRight) {
    PsiModifierListOwner leftPsi = dfaLeft.getPsiVariable();
    PsiModifierListOwner rightPsi = dfaRight.getPsiVariable();
    if (leftPsi != rightPsi || !(leftPsi instanceof PsiMethod) || !PsiTypesUtil.isGetClass((PsiMethod)leftPsi)) return true;
    DfaVariableValue leftQualifier = dfaLeft.getQualifier();
    DfaVariableValue rightQualifier = dfaRight.getQualifier();
    if (leftQualifier == null || rightQualifier == null || leftQualifier == rightQualifier) return true;
    TypeConstraint leftType = getValueFact(leftQualifier, DfaFactType.TYPE_CONSTRAINT);
    TypeConstraint rightType = getValueFact(rightQualifier, DfaFactType.TYPE_CONSTRAINT);
    return applyFact(leftQualifier, DfaFactType.TYPE_CONSTRAINT, rightType) &&
           applyFact(rightQualifier, DfaFactType.TYPE_CONSTRAINT, leftType);
  }

  @NotNull
  private ThreeState processGetClass(DfaVariableValue variable, PsiType value, boolean negated) {
    EqClass eqClass = getEqClass(variable);
    List<DfaVariableValue> variables = eqClass == null ? Collections.singletonList(variable)
                                                       : eqClass.getVariables(false);
    boolean hasUnprocessed = false;
    for (DfaVariableValue var : variables) {
      PsiModifierListOwner psi = var.getPsiVariable();
      DfaVariableValue qualifier = var.getQualifier();
      if (psi instanceof PsiMethod && PsiTypesUtil.isGetClass((PsiMethod)psi) && qualifier != null) {
        switch (applyGetClassRelation(qualifier, value, negated)) {
          case NO:
            return ThreeState.NO;
          case YES:
            continue;
          case UNSURE:
            break;
        }
      }
      hasUnprocessed = true;
    }
    return hasUnprocessed ? ThreeState.UNSURE : ThreeState.YES;
  }

  @NotNull
  private ThreeState applyGetClassRelation(@NotNull DfaVariableValue qualifier, @NotNull PsiType value, boolean negated) {
    DfaPsiType dfaType = myFactory.createDfaType(value);
    TypeConstraint constraint = TypeConstraint.exact(dfaType);
    PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(value);
    if (psiClass != null && (psiClass.isInterface() || psiClass.hasModifierProperty(PsiModifier.ABSTRACT))) {
      // getClass() result cannot be an interface or an abstract class
      return ThreeState.fromBoolean(negated);
    }
    if (!negated) {
      return ThreeState.fromBoolean(applyFact(qualifier, DfaFactType.TYPE_CONSTRAINT, constraint));
    }
    TypeConstraint existingConstraint = getValueFact(qualifier, DfaFactType.TYPE_CONSTRAINT);
    if (existingConstraint != null && existingConstraint.isExact()) {
      return ThreeState.fromBoolean(!existingConstraint.equals(constraint));
    }
    if (dfaType.asConstraint().isExact()) { // final class
      return ThreeState.fromBoolean(
        applyFact(qualifier, DfaFactType.TYPE_CONSTRAINT, TypeConstraint.empty().withNotInstanceofValue(dfaType)));
    }
    return ThreeState.UNSURE;
  }

  private boolean applyRangeToRelatedValues(DfaValue value, LongRangeSet appliedRange) {
    EqClass eqClass = getEqClass(value);
    if (eqClass != null) {
      if (!applyRelationRangeToClass(eqClass, appliedRange, RelationType.EQ)) return false;
      for (DistinctPairSet.DistinctPair pair : getDistinctClassPairs().toArray(new DistinctPairSet.DistinctPair[0])) {
        if (pair.isOrdered()) {
          if (pair.getFirst() == eqClass) {
            if (!applyRelationRangeToClass(pair.getSecond(), appliedRange, RelationType.GT)) return false;
          } else if(pair.getSecond() == eqClass) {
            if (!applyRelationRangeToClass(pair.getFirst(), appliedRange, RelationType.LT)) return false;
          }
        } else if(appliedRange.getConstantValue() != null) {
          EqClass other = pair.getFirst() == eqClass ? pair.getSecond() : pair.getSecond() == eqClass ? pair.getFirst() : null;
          if (other != null) {
            if (!applyRelationRangeToClass(other, appliedRange, RelationType.NE)) return false;
          }
        }
      }
    }
    return true;
  }

  private boolean applyRelationRangeToClass(EqClass eqClass, LongRangeSet range, RelationType relationType) {
    LongRangeSet appliedRange = range.fromRelation(relationType);
    for (DfaVariableValue var : eqClass.getVariables(false)) {
      if (!applyFact(var, DfaFactType.RANGE, appliedRange)) return false;
    }
    return true;
  }

  private Couple<DfaValue> getSpecialEquivalencePair(DfaVariableValue left, DfaValue right) {
    if (right instanceof DfaVariableValue) return null;
    SpecialField field = SpecialField.fromQualifier(left);
    if (field == null) return null;
    DfaValue leftValue = field.createValue(myFactory, left);
    DfaValue rightValue = field.createValue(myFactory, right);
    return rightValue.equals(field.getDefaultValue(myFactory, false)) ? null : Couple.of(leftValue, rightValue);
  }

  private boolean applySpecialFieldEquivalence(@NotNull DfaValue left, @NotNull DfaValue right) {
    Couple<DfaValue> pair = left instanceof DfaVariableValue ? getSpecialEquivalencePair((DfaVariableValue)left, right) :
                            right instanceof DfaVariableValue ? getSpecialEquivalencePair((DfaVariableValue)right, left) : null;
    return pair == null || applyCondition(pair.getFirst().eq(pair.getSecond()));
  }

  private boolean applyUnboxedRelation(@NotNull DfaValue dfaLeft, DfaValue dfaRight, boolean negated) {
    if (dfaLeft instanceof DfaVariableValue && !TypeConversionUtil.isPrimitiveWrapper(dfaLeft.getType()) ||
        dfaRight instanceof DfaVariableValue && !TypeConversionUtil.isPrimitiveWrapper(dfaRight.getType())) {
      return true;
    }
    PsiType leftType = getPsiType(dfaLeft);
    PsiType rightType = getPsiType(dfaRight);
    if (TypeConversionUtil.isPrimitiveWrapper(leftType) &&
        TypeConversionUtil.isPrimitiveWrapper(rightType) && !leftType.equals(rightType)) {
      // Boxes of different type (e.g. Long and Integer), cannot be equal even if unboxed values are equal
      return negated;
    }

    DfaValue unboxedLeft = SpecialField.UNBOX.createValue(myFactory, dfaLeft);
    DfaValue unboxedRight = SpecialField.UNBOX.createValue(myFactory, dfaRight);
    DfaConstValue leftConst = getConstantValue(unboxedLeft);
    DfaConstValue rightConst = getConstantValue(unboxedRight);
    if (leftConst != null && rightConst != null) {
      return Objects.equals(leftConst.getValue(), rightConst.getValue()) != negated;
    }
    if (negated && (PsiType.FLOAT.equals(unboxedLeft.getType()) || PsiType.DOUBLE.equals(unboxedLeft.getType()))) {
      // If floating point wrappers are not equal, unboxed versions could still be equal if they are 0.0 and -0.0
      return true;
    }
    return applyRelation(unboxedLeft, unboxedRight, negated);
  }

  @Nullable
  private PsiType getPsiType(@NotNull DfaValue value) {
    TypeConstraint constraint = getValueFact(value, DfaFactType.TYPE_CONSTRAINT);
    if (constraint != null) {
      PsiType type = constraint.getPsiType();
      if (type != null) {
        return type;
      }
    }
    return value.getType();
  }

  private boolean checkCompareWithBooleanLiteral(DfaValue dfaLeft, DfaValue dfaRight, boolean negated) {
    if (dfaRight instanceof DfaConstValue) {
      Object constVal = ((DfaConstValue)dfaRight).getValue();
      if (constVal instanceof Boolean) {
        boolean boolValue = (Boolean)constVal;
        return applyRelation(dfaLeft, myFactory.getBoolean(!boolValue), !negated) &&
               applyRelation(dfaLeft, myFactory.getBoolean(boolValue), negated);
      }
    }
    return true;
  }

  static boolean isNaN(final DfaValue dfa) {
    return dfa instanceof DfaConstValue && DfaUtil.isNaN(((DfaConstValue)dfa).getValue());
  }

  private boolean applyRelation(@NotNull final DfaValue dfaLeft, @NotNull final DfaValue dfaRight, boolean isNegated) {
    // DfaConstValue || DfaVariableValue
    Integer c1Index = getOrCreateEqClassIndex(dfaLeft);
    Integer c2Index = getOrCreateEqClassIndex(dfaRight);
    if (c1Index == null || c2Index == null) return true;
    if (c1Index.equals(c2Index)) return !isNegated;

    RelationType constantRelation = getConstantRelation(dfaLeft, dfaRight);
    if (constantRelation != null) {
      return (constantRelation == RelationType.EQ) != isNegated;
    }
    if (!isNegated) { //Equals
      if (isUnstableValue(dfaLeft) || isUnstableValue(dfaRight)) return true;
      if (!uniteClasses(dfaLeft, dfaRight)) return false;

      for (Iterator<DistinctPairSet.DistinctPair> iterator = myDistinctClasses.iterator(); iterator.hasNext(); ) {
        DistinctPairSet.DistinctPair pair = iterator.next();
        DfaConstValue const1 = pair.getFirst().findConstant();
        DfaConstValue const2 = pair.getSecond().findConstant();
        if (const1 != null && const2 != null && !preserveConstantDistinction(const1.getValue(), const2.getValue())) {
          iterator.remove();
        }
      }
      myCachedNonTrivialEqClasses = null;
    }
    else { // Not Equals
      if (isNull(dfaLeft) && isPrimitive(dfaRight) || isNull(dfaRight) && isPrimitive(dfaLeft)) return true;
      if (applyBooleanInequality(dfaLeft, dfaRight) ||
          applyBooleanInequality(dfaRight, dfaLeft)) {
        return true;
      }
      myDistinctClasses.addUnordered(c1Index, c2Index);
    }
    myCachedHash = null;

    return true;
  }

  private boolean applyBooleanInequality(DfaValue var, DfaValue value) {
    if (!(var instanceof DfaVariableValue) ||
        !PsiType.BOOLEAN.equals(var.getType()) ||
        ((DfaVariableValue)var).getDescriptor() == SpecialField.UNBOX) {
      return false;
    }
    if (!(value instanceof DfaConstValue)) return false;
    Boolean constValue = ObjectUtils.tryCast(((DfaConstValue)value).getValue(), Boolean.class);
    return constValue != null && applyRelation(var, myFactory.getBoolean(!constValue), false);
  }

  private boolean applyLessThanRelation(@NotNull final DfaValue dfaLeft, @NotNull final DfaValue dfaRight) {
    // DfaConstValue || DfaVariableValue
    Integer c1Index = getOrCreateEqClassIndex(dfaLeft);
    Integer c2Index = getOrCreateEqClassIndex(dfaRight);
    if (c1Index == null || c2Index == null) return true;
    if (c1Index.equals(c2Index)) return false;

    RelationType constantRelation = getConstantRelation(dfaLeft, dfaRight);
    if (constantRelation != null) {
      return constantRelation == RelationType.LT;
    }
    if (isNull(dfaLeft) && isPrimitive(dfaRight) || isNull(dfaRight) && isPrimitive(dfaLeft)) return true;
    myCachedHash = null;
    return myDistinctClasses.addOrdered(c1Index, c2Index);
  }

  /**
   * Returns true if value represents an "unstable" value. An unstable value is a value of an object type which could be
   * a newly object every time it's accessed. Such value is still useful as its nullability is stable
   *
   * @param value to check.
   * @return true if value might be unstable, false otherwise
   */
  private boolean isUnstableValue(DfaValue value) {
    if (!(value instanceof DfaVariableValue)) return false;
    DfaVariableValue var = (DfaVariableValue)value;
    PsiModifierListOwner owner = var.getPsiVariable();
    if (!(owner instanceof PsiMethod)) return false;
    if (var.getType() instanceof PsiPrimitiveType) return false;
    if (PropertyUtilBase.isSimplePropertyGetter((PsiMethod)owner)) return false;
    if (isNull(var)) return false;
    return true;
  }

  private static boolean isPrimitive(DfaValue value) {
    return value instanceof DfaVariableValue &&
           value.getType() instanceof PsiPrimitiveType &&
           !PsiType.VOID.equals(value.getType()); // void is used for temporary variables sometimes
  }

  private static boolean preserveConstantDistinction(final Object c1, final Object c2) {
    return c1 == null && c2 instanceof PsiVariable ||
           c2 == null && c1 instanceof PsiVariable;
  }

  @Nullable
  private RelationType getConstantRelation(DfaValue val1, DfaValue val2) {
    DfaConstValue const1 = getConstantValue(val1, false);
    DfaConstValue const2 = getConstantValue(val2, false);
    if (const1 == null || const2 == null) return null;
    Number value1 = ObjectUtils.tryCast(const1.getValue(), Number.class);
    Number value2 = ObjectUtils.tryCast(const2.getValue(), Number.class);
    if (value1 == null || value2 == null) return null;
    int cmp;
    if (value1 instanceof Long && value2 instanceof Long) {
      cmp = Long.compare((Long)value1, (Long)value2);
    } else {
      double double1 = value1.doubleValue();
      double double2 = value2.doubleValue();
      if (double1 == 0.0 && double2 == 0.0) return RelationType.EQ;
      cmp = Double.compare(double1, double2);
    }
    return cmp == 0 ? RelationType.EQ : cmp < 0 ? RelationType.LT : RelationType.GT;
  }

  @Override
  public boolean checkNotNullable(DfaValue value) {
    if (value == myFactory.getConstFactory().getNull()) return false;
    if (value instanceof DfaFactMapValue && DfaNullability.isNullable(((DfaFactMapValue)value).getFacts())) return false;

    if (value instanceof DfaVariableValue) {
      DfaVariableValue varValue = (DfaVariableValue)value;
      if (varValue.getType() instanceof PsiPrimitiveType) return true;
      if (isNotNull(varValue)) return true;
      return getVariableState(varValue).getNullability() != Nullability.NULLABLE;
    }
    return true;
  }

  @Override
  @Nullable
  public <T> T getValueFact(@NotNull DfaValue value, @NotNull DfaFactType<T> factType) {
    if (value instanceof DfaBinOpValue && factType == DfaFactType.RANGE) {
      //noinspection unchecked
      return (T)getBinOpRange((DfaBinOpValue)value);
    }
    if (value instanceof DfaVariableValue) {
      DfaVariableValue var = (DfaVariableValue)value;
      DfaVariableState state = getExistingVariableState(var);
      if (state != null) {
        T fact = state.getFact(factType);
        if (fact != null) {
          return fact;
        }
      }
      value = resolveVariableValue(var);
    }
    return factType.fromDfaValue(value);
  }

  @Nullable
  public LongRangeSet getBinOpRange(DfaBinOpValue binOp) {
    LongRangeSet left = getValueFact(binOp.getLeft(), DfaFactType.RANGE);
    LongRangeSet right = getValueFact(binOp.getRight(), DfaFactType.RANGE);
    if (left == null || right == null) return null;
    boolean isLong = PsiType.LONG.equals(binOp.getType());
    LongRangeSet result = left.binOpFromToken(binOp.getTokenType(), right, isLong);
    if (result != null && binOp.getOperation() == DfaBinOpValue.BinOp.MINUS) {
      RelationType rel = getRelation(binOp.getLeft(), binOp.getRight());
      if (rel == RelationType.NE) {
        return result.without(0);
      }
      if (!left.subtractionMayOverflow(right, isLong)) {
        if (rel == RelationType.GT) {
          return result.intersect(LongRangeSet.range(1, isLong ? Long.MAX_VALUE : Integer.MAX_VALUE));
        }
        if (rel == RelationType.LT) {
          return result.intersect(LongRangeSet.range(isLong ? Long.MIN_VALUE : Integer.MIN_VALUE, -1));
        }
      }
    }
    if (binOp.getOperation() == DfaBinOpValue.BinOp.PLUS && areEqual(binOp.getLeft(), binOp.getRight())) {
      return LongRangeSet.point(2).mul(left, isLong);
    }
    return result;
  }

  @Override
  public <T> void forceVariableFact(@NotNull DfaVariableValue var, @NotNull DfaFactType<T> factType, @Nullable T value) {
    DfaVariableState state = getVariableState(var);
    if (factType.equals(DfaFactType.NULLABILITY)) {
      if (value == DfaNullability.NOT_NULL && isNull(var) ||
          value == DfaNullability.NULLABLE && isNotNull(var)) {
        removeEquivalenceForVariableAndWrappers(var);
      }
    }
    setVariableState(var, state.withFact(factType, value));
    updateEqClassesByState(var);
  }

  @NotNull
  private DfaValue resolveVariableValue(DfaVariableValue var) {
    DfaConstValue constValue = getConstantValue(var, false);
    if (constValue != null) {
      return constValue;
    }
    return var;
  }

  @NotNull
  @Override
  public DfaFactMap getFacts(@NotNull DfaVariableValue variable) {
    return getVariableState(variable).myFactMap;
  }

  DfaFactMap getFactMap(@NotNull DfaValue value) {
    if (value instanceof DfaVariableValue) {
      DfaVariableState state = getExistingVariableState((DfaVariableValue)value);
      if (state != null) {
        return state.myFactMap;
      }
      value = resolveVariableValue((DfaVariableValue)value);
      if (value instanceof DfaVariableValue) {
        return getDefaultState((DfaVariableValue)value).myFactMap;
      }
    }
    if (value instanceof DfaBinOpValue) {
      return DfaFactMap.EMPTY.with(DfaFactType.RANGE, getValueFact(value, DfaFactType.RANGE));
    }
    return DfaFactMap.fromDfaValue(value);
  }

  void setVariableState(@NotNull DfaVariableValue dfaVar, @NotNull DfaVariableState state) {
    dfaVar = canonicalize(dfaVar);
    if (state.equals(getDefaultState(dfaVar))) {
      myVariableStates.remove(dfaVar);
    } else {
      myVariableStates.put(dfaVar, state);
    }
    myCachedHash = null;
  }

  protected void updateEquivalentVariables(DfaVariableValue dfaVar, DfaVariableState state) {
    EqClass eqClass = getEqClass(dfaVar);
    if (eqClass != null) {
      for (DfaValue value : eqClass.getMemberValues()) {
        if (value != dfaVar && value instanceof DfaVariableValue) {
          setVariableState((DfaVariableValue)value, state);
        }
      }
    }
  }

  @NotNull
  private DfaValue canonicalize(@NotNull DfaValue value) {
    if (value instanceof DfaVariableValue) {
      return canonicalize((DfaVariableValue)value);
    }
    if (value instanceof DfaBoxedValue) {
      DfaBoxedValue boxedValue = (DfaBoxedValue)value;
      DfaValue canonicalized = canonicalize(boxedValue.getWrappedValue());
      return Objects.requireNonNull(myFactory.getBoxedFactory().createBoxed(canonicalized, boxedValue.getType()));
    }
    return value;
  }

  @NotNull
  private DfaVariableValue canonicalize(DfaVariableValue var) {
    DfaVariableValue qualifier = var.getQualifier();
    if (qualifier != null) {
      Integer index = myIdToEqClassesIndices.get(qualifier.getID());
      if (index == null) {
        qualifier = canonicalize(qualifier);
        index = myIdToEqClassesIndices.get(qualifier.getID());
        if (index == null) {
          return var.withQualifier(qualifier);
        }
      }

      return var.withQualifier(Objects.requireNonNull(myEqClasses.get(index).getCanonicalVariable()));
    }
    return var;
  }

  private DfaVariableState getExistingVariableState(DfaVariableValue var) {
    DfaVariableState state = myVariableStates.get(var);
    if (state != null) {
      return state;
    }
    DfaVariableValue canonicalized = canonicalize(var);
    return canonicalized == var ? null : myVariableStates.get(canonicalized);
  }

  @NotNull
  DfaVariableState getVariableState(DfaVariableValue dfaVar) {
    DfaVariableState state = getExistingVariableState(dfaVar);
    return state != null ? state : getDefaultState(dfaVar);
  }

  @NotNull
  private DfaVariableState getDefaultState(DfaVariableValue dfaVar) {
    return myDefaultVariableStates.computeIfAbsent(dfaVar, this::createVariableState);
  }

  void forVariableStates(BiConsumer<? super DfaVariableValue, ? super DfaVariableState> consumer) {
    myVariableStates.forEach(consumer);
  }

  @NotNull
  protected DfaVariableState createVariableState(@NotNull DfaVariableValue var) {
    return new DfaVariableState(var);
  }

  @Override
  public void flushFields() {
    Set<DfaVariableValue> vars = new LinkedHashSet<>(getChangedVariables());
    for (EqClass aClass : myEqClasses) {
      if (aClass != null) {
        vars.addAll(aClass.getVariables(true));
      }
    }
    for (DfaVariableValue value : vars) {
      if (!value.isFlushableByCalls()) continue;
      DfaVariableValue qualifier = value.getQualifier();
      if (qualifier != null) {
        if (getValueFact(qualifier, DfaFactType.MUTABILITY) == Mutability.UNMODIFIABLE ||
            Boolean.TRUE.equals(getValueFact(qualifier, DfaFactType.LOCALITY))) {
          continue;
        }
      }
      doFlush(value, shouldMarkFlushed(value));
    }
    myStack.replaceAll(val -> {
      if (val instanceof DfaFactMapValue) {
        DfaFactMapValue factMapValue = (DfaFactMapValue)val;
        SpecialFieldValue sfValue = factMapValue.get(DfaFactType.SPECIAL_FIELD_VALUE);
        if (sfValue != null && !sfValue.getField().isStable() && factMapValue.get(DfaFactType.MUTABILITY) != Mutability.UNMODIFIABLE) {
          return factMapValue.withFact(DfaFactType.SPECIAL_FIELD_VALUE, null);
        }
      }
      return val;
    });
  }

  private boolean shouldMarkFlushed(@NotNull DfaVariableValue value) {
    if (value.getInherentNullability() != Nullability.NULLABLE) return false;
    return getVariableState(value).getFact(DfaFactType.NULLABILITY) == DfaNullability.FLUSHED || isNull(value) || isNotNull(value);
  }

  @NotNull
  Set<DfaVariableValue> getChangedVariables() {
    return myVariableStates.keySet();
  }

  @Override
  public void flushVariable(@NotNull final DfaVariableValue variable) {
    flushVariable(variable, false);
  }

  protected void flushVariable(@NotNull final DfaVariableValue variable, boolean shouldMarkFlushed) {
    EqClass eqClass = variable.getDependentVariables().isEmpty() ? null : getEqClass(variable);
    DfaVariableValue newCanonical =
      eqClass == null ? null : StreamEx.of(eqClass.getVariables(false)).without(variable).min(EqClass.CANONICAL_VARIABLE_COMPARATOR)
        .filter(candidate -> !candidate.dependsOn(variable))
        .orElse(null);
    myStack.replaceAll(value -> handleStackValueOnVariableFlush(value, variable, newCanonical));

    doFlush(variable, shouldMarkFlushed);
    flushDependencies(variable);
    myCachedHash = null;
  }

  void flushDependencies(@NotNull DfaVariableValue variable) {
    for (DfaVariableValue dependent : variable.getDependentVariables().toArray(new DfaVariableValue[0])) {
      doFlush(dependent, false);
    }
  }

  private void flushQualifiedMethods(@NotNull DfaVariableValue variable) {
    PsiModifierListOwner psiVariable = variable.getPsiVariable();
    DfaVariableValue qualifier = variable.getQualifier();
    if (psiVariable instanceof PsiField && qualifier != null) {
      // Flush method results on field write
      List<DfaVariableValue> toFlush =
        ContainerUtil.filter(qualifier.getDependentVariables(), DfaVariableValue::containsCalls);
      toFlush.forEach(val -> doFlush(val, shouldMarkFlushed(val)));
    }
  }

  void doFlush(@NotNull DfaVariableValue var, boolean markFlushed) {
    if(isNull(var)) {
      myStack.replaceAll(val -> val == var ? myFactory.getConstFactory().getNull() : val);
    }

    removeEquivalenceForVariableAndWrappers(var);
    myVariableStates.remove(var);
    if (markFlushed) {
      setVariableState(var, getVariableState(var).withFact(DfaFactType.NULLABILITY, DfaNullability.FLUSHED));
    }
    myCachedHash = null;
  }

  private void removeEquivalence(DfaValue var) {
    int varID = var.getID();
    Integer varClassIndex = myIdToEqClassesIndices.get(varID);
    if (varClassIndex == null) {
      var = canonicalize(var);
      varID = var.getID();
      varClassIndex = myIdToEqClassesIndices.get(varID);
      if (varClassIndex == null) return;
    }

    EqClass varClass = myEqClasses.get(varClassIndex);

    varClass = new EqClass(varClass);
    DfaVariableValue previousCanonical = varClass.getCanonicalVariable();
    myEqClasses.set(varClassIndex, varClass);
    varClass.removeValue(varID);
    myIdToEqClassesIndices.remove(varID);
    checkInvariants();

    if (varClass.isEmpty()) {
      myEqClasses.set(varClassIndex, null);

      for (Iterator<DistinctPairSet.DistinctPair> iterator = myDistinctClasses.iterator(); iterator.hasNext(); ) {
        DistinctPairSet.DistinctPair pair = iterator.next();
        if (pair.getOtherClass(varClassIndex) != null) {
          iterator.remove();
        }
      }
    }
    else if (varClass.containsConstantsOnly()) {
      for (Iterator<DistinctPairSet.DistinctPair> iterator = myDistinctClasses.iterator(); iterator.hasNext(); ) {
        DistinctPairSet.DistinctPair pair = iterator.next();
        EqClass other = pair.getOtherClass(varClassIndex);
        if (other != null && other.containsConstantsOnly()) {
          iterator.remove();
        }
      }
    }
    else {
      DfaVariableValue newCanonical = varClass.getCanonicalVariable();
      if (newCanonical != null && previousCanonical != null && previousCanonical != newCanonical &&
          (ControlFlowAnalyzer.isTempVariable(previousCanonical) && !newCanonical.dependsOn(previousCanonical) ||
           newCanonical.getDepth() <= previousCanonical.getDepth())) {
        // Do not transfer to deeper qualifier. E.g. if we have two classes like (a, b.c) (a.d, e),
        // and flushing `a`, we do not convert `a.d` to `b.c.d`. Otherwise infinite qualifier explosion is possible.
        boolean successfullyConverted = convertQualifiers(previousCanonical, newCanonical);
        assert successfullyConverted;
      }
    }

    myCachedNonTrivialEqClasses = null;
    myCachedHash = null;
  }

  void removeEquivalenceForVariableAndWrappers(@NotNull DfaVariableValue var) {
    removeEquivalence(var);
    DfaValue wrapped = myFactory.getBoxedFactory().getBoxedIfExists(var);
    if (wrapped != null) {
      removeEquivalence(wrapped);
    }
  }

  /**
   * @return a mergeability key. If two states return the same key, then states could be merged via {@link #merge(DfaMemoryStateImpl)}.
   */
  Object getMergeabilityKey() {
    /*
      States are mergeable if:
      - Ephemeral flag is the same
      - Stack depth is the same
      - All DfaControlTransferValues in the stack are the same (otherwise finally blocks may not complete successfully)
      - Top-of-stack value is the same (otherwise we may prematurely merge true/false on TOS right before jump which is very undesired)
     */
    return StreamEx.of(myStack).<Object>mapLastOrElse(val -> ObjectUtils.tryCast(val, DfaControlTransferValue.class),
                                                      Function.identity())
      .append(isEphemeral()).toImmutableList();
  }

  /**
   * Updates this DfaMemoryState so that it becomes a minimal superstate which covers the other state as well
   *
   * @param other other state which has equal {@link #getMergeabilityKey()}
   */
  void merge(DfaMemoryStateImpl other) {
    assert other.isEphemeral() == isEphemeral();
    assert other.myStack.size() == myStack.size();
    ProgressManager.checkCanceled();
    retainEquivalences(other);
    mergeDistinctPairs(other);
    mergeVariableStates(other);
    mergeStacks(other);
    myCachedHash = null;
    myCachedNonTrivialEqClasses = null;
    afterMerge(other);
  }

  /**
   * Custom logic to be implemented by subclasses
   * @param other
   */
  protected void afterMerge(DfaMemoryStateImpl other) {

  }

  private void mergeStacks(DfaMemoryStateImpl other) {
    List<DfaValue> values = StreamEx.zip(myStack, other.myStack, DfaValue::unite).toList();
    myStack.clear();
    values.forEach(myStack::push);
  }

  private void mergeDistinctPairs(DfaMemoryStateImpl other) {
    ArrayList<DistinctPairSet.DistinctPair> pairs = new ArrayList<>(myDistinctClasses);
    for (DistinctPairSet.DistinctPair pair : pairs) {
      EqClass first = pair.getFirst();
      EqClass second = pair.getSecond();
      RelationType relation = other.getRelation(myFactory.getValue(first.get(0)), myFactory.getValue(second.get(0)));
      if (relation == null || relation == RelationType.EQ) {
        myDistinctClasses.remove(pair);
      }
      else if (pair.isOrdered() && relation != RelationType.LT) {
        myDistinctClasses.dropOrder(pair);
      }
    }
  }

  private void mergeVariableStates(DfaMemoryStateImpl other) {
    Set<DfaVariableValue> vars = StreamEx.of(myVariableStates, other.myVariableStates).toFlatCollection(Map::keySet, HashSet::new);
    for (DfaVariableValue var : vars) {
      DfaVariableState state = getVariableState(var);
      DfaVariableState otherState = other.getVariableState(var);
      DfaFactMap result = state.myFactMap.unite(otherState.myFactMap);
      Nullability nullability = state.getNullability();
      Nullability otherNullability = otherState.getNullability();
      if (nullability != otherNullability && (nullability == Nullability.NULLABLE || otherNullability == Nullability.NULLABLE)) {
        // When merging nullable with something we cannot warn about nullability violation anymore
        // because we lose the information about coherent state, thus noise warnings could be produced
        result = result.with(DfaFactType.NULLABILITY, DfaNullability.FLUSHED);
      }
      setVariableState(var, state.withFacts(result));
    }
  }

  private void retainEquivalences(DfaMemoryStateImpl other) {
    boolean needRestart = true;
    while (needRestart) {
      ProgressManager.checkCanceled();
      needRestart = false;
      for (EqClass eqClass : new ArrayList<>(myEqClasses)) {
        if (eqClass != null && retainEquivalences(eqClass, other)) {
          needRestart = true;
          break;
        }
      }
    }
  }

  /**
   * Retain only those equivalences from given class which are present in other memory state
   *
   * @param eqClass an equivalence class to process
   * @param other   other memory state. If it does not contain all the equivalences from the eqClass, the eqClass will
   *                be split to retain only remaining equivalences
   * @return true if not only given class, but also some other classes were updated due to canonicalization
   */
  private boolean retainEquivalences(EqClass eqClass, DfaMemoryStateImpl other) {
    if (eqClass.size() <= 1) return false;
    List<EqClass> groups = splitEqClass(eqClass, other);
    if (groups.size() == 1) return false;

    TIntArrayList addedClasses = new TIntArrayList();
    int origIndex = myIdToEqClassesIndices.get(eqClass.get(0));
    for (EqClass group : groups) {
      addedClasses.add(storeClass(group));
    }
    int[] addedClassesArray = addedClasses.toNativeArray();
    myDistinctClasses.splitClass(origIndex, addedClassesArray);
    myEqClasses.set(origIndex, null);

    DfaVariableValue from = eqClass.getCanonicalVariable();
    boolean otherClassChanged = false;
    if (from != null && !from.getDependentVariables().isEmpty()) {
      List<DfaVariableValue> vars = new ArrayList<>(myVariableStates.keySet());
      for (int classIndex : addedClassesArray) {
        DfaVariableValue to = myEqClasses.get(classIndex).getCanonicalVariable();
        if (to == null || to == from || to.getDepth() > from.getDepth()) continue;

        for (DfaVariableValue var : vars) {
          DfaVariableValue target = replaceQualifier(var, from, to);
          if (target != var) {
            setVariableState(target, getVariableState(var));
          }
        }
        for (int valueId : myIdToEqClassesIndices.keys()) {
          DfaValue value = myFactory.getValue(valueId);
          DfaVariableValue var = ObjectUtils.tryCast(value, DfaVariableValue.class);
          if (var == null || var.getQualifier() != from) continue;
          DfaVariableValue target = var.withQualifier(to);
          boolean united = uniteClasses(var, target);
          assert united;
          otherClassChanged = true;
        }
      }
    }
    checkInvariants();
    return otherClassChanged;
  }

  /**
   * Splits given EqClass to several classes removing equivalences absent in other state
   *
   * @param eqClass an equivalence class to split
   * @param other   other memory state; only equivalences present in that state should be preserved
   * @return list of created classes (the original class remains unchanged). Trivial classes are also included,
   * thus sum of resulting class sizes is equal to the original class size
   */
  @NotNull
  private List<EqClass> splitEqClass(EqClass eqClass, DfaMemoryStateImpl other) {
    TIntObjectHashMap<EqClass> groupsInClasses = new TIntObjectHashMap<>();
    List<EqClass> groups = new ArrayList<>();
    for (DfaValue value : eqClass.getMemberValues()) {
      int otherClass = other.getEqClassIndex(value);
      EqClass list;
      if (otherClass == -1) {
        list = new EqClass(myFactory);
        groups.add(list);
      }
      else {
        list = groupsInClasses.get(otherClass);
        if (list == null) {
          list = new EqClass(myFactory);
          groupsInClasses.put(otherClass, list);
        }
      }
      list.add(value.getID());
    }
    groupsInClasses.forEachValue(groups::add);
    return groups;
  }

  private class MyIdMap extends TIntObjectHashMap<Integer> {
    @Override
    public String toString() {
      final StringBuilder s = new StringBuilder("{");
      forEachEntry(new TIntObjectProcedure<Integer>() {
        @Override
        public boolean execute(int id, Integer index) {
          DfaValue value = myFactory.getValue(id);
          s.append(value).append(" -> ").append(index).append(", ");
          return true;
        }
      });
      s.append("}");
      return s.toString();
    }
  }
}
