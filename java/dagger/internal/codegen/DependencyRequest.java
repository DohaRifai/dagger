/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.auto.common.MoreTypes.asDeclared;
import static com.google.auto.common.MoreTypes.isType;
import static com.google.auto.common.MoreTypes.isTypeOf;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.ConfigurationAnnotations.getNullableType;
import static dagger.internal.codegen.Optionals.firstPresent;
import static dagger.internal.codegen.RequestKinds.frameworkClass;
import static dagger.model.RequestKind.FUTURE;
import static dagger.model.RequestKind.INSTANCE;
import static dagger.model.RequestKind.LAZY;
import static dagger.model.RequestKind.MEMBERS_INJECTION;
import static dagger.model.RequestKind.PRODUCER;
import static dagger.model.RequestKind.PROVIDER;
import static dagger.model.RequestKind.PROVIDER_OF_LAZY;

import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import dagger.Lazy;
import dagger.MembersInjector;
import dagger.Provides;
import dagger.model.Key;
import dagger.model.RequestKind;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleTypeVisitor7;

/**
 * Represents a request for a key at an injection point. Parameters to {@link Inject} constructors
 * or {@link Provides} methods are examples of key requests.
 *
 * @author Gregory Kick
 * @since 2.0
 */
// TODO(gak): Set bindings and the permutations thereof need to be addressed
@AutoValue
abstract class DependencyRequest {
  abstract RequestKind kind();
  abstract Key key();

  BindingKey bindingKey() {
    switch (kind()) {
      case INSTANCE:
      case LAZY:
      case PROVIDER:
      case PROVIDER_OF_LAZY:
      case PRODUCER:
      case PRODUCED:
      case FUTURE:
        return BindingKey.contribution(key());
      case MEMBERS_INJECTION:
        return BindingKey.membersInjection(key());
      default:
        throw new AssertionError(this);
    }
  }

  /** The element that declares this dependency request. Absent for synthetic requests. */
  abstract Optional<Element> requestElement();

  /**
   * Returns {@code true} if {@code requestElement}'s type is a primitive type.
   *
   * <p>Because the {@link #key()} of a {@link DependencyRequest} is {@linkplain
   * KeyFactory#boxPrimitives(TypeMirror) boxed} to normalize it with other keys, this inspects the
   * {@link #requestElement()} directly.
   */
  boolean requestsPrimitiveType() {
    return requestElement().map(element -> element.asType().getKind().isPrimitive()).orElse(false);
  }

  /** Returns true if this request allows null objects. */
  abstract boolean isNullable();

  private static DependencyRequest.Builder builder() {
    return new AutoValue_DependencyRequest.Builder().isNullable(false);
  }

  /**
   * Extracts the dependency request type and kind from the type of a dependency request element.
   * For example, if a user requests {@code Provider<Foo>}, this will return ({@link
   * RequestKind#PROVIDER}, {@code Foo}).
   *
   * @throws TypeNotPresentException if {@code type}'s kind is {@link TypeKind#ERROR}, which may
   *     mean that the type will be generated in a later round of processing
   */
  static KindAndType extractKindAndType(TypeMirror type) {
    return type.accept(
        new SimpleTypeVisitor7<KindAndType, Void>() {
          @Override
          public KindAndType visitError(ErrorType errorType, Void p) {
            throw new TypeNotPresentException(errorType.toString(), null);
          }

          @Override
          public KindAndType visitExecutable(ExecutableType executableType, Void p) {
            return executableType.getReturnType().accept(this, null);
          }

          @Override
          public KindAndType visitDeclared(DeclaredType declaredType, Void p) {
            return KindAndType.from(declaredType).orElse(defaultAction(declaredType, p));
          }

          @Override
          protected KindAndType defaultAction(TypeMirror otherType, Void p) {
            return new KindAndType(INSTANCE, otherType);
          }
        },
        null);
  }

  static final class KindAndType {
    private final RequestKind kind;
    private final TypeMirror type;

    KindAndType(RequestKind kind, TypeMirror type) {
      this.kind = checkNotNull(kind);
      this.type = checkNotNull(type);
    }

    RequestKind kind() {
      return kind;
    }

    TypeMirror type() {
      return type;
    }

    static Optional<KindAndType> from(TypeMirror type) {
      for (RequestKind kind : RequestKind.values()) {
        Optional<KindAndType> kindAndType = from(kind, type);
        if (kindAndType.isPresent()) {
          return firstPresent(kindAndType.get().maybeProviderOfLazy(), kindAndType);
        }
      }
      return Optional.empty();
    }

    /**
     * If {@code type}'s raw type is {@link RequestKinds#frameworkClass framework class}, returns a
     * {@link KindAndType} with this kind that represents the dependency request.
     */
    private static Optional<KindAndType> from(RequestKind kind, TypeMirror type) {
      Optional<Class<?>> frameworkClass = frameworkClass(kind);
      if (frameworkClass.isPresent() && isType(type) && isTypeOf(frameworkClass.get(), type)) {
        List<? extends TypeMirror> typeArguments = asDeclared(type).getTypeArguments();
        if (typeArguments.isEmpty()) {
          return Optional.empty();
        }
        return Optional.of(new KindAndType(kind, getOnlyElement(typeArguments)));
      }
      return Optional.empty();
    }

    /**
     * If {@code kindAndType} represents a {@link RequestKind#PROVIDER} of a {@code Lazy<T>} for
     * some type {@code T}, then this method returns ({@link RequestKind#PROVIDER_OF_LAZY}, {@code
     * T}).
     */
    private Optional<KindAndType> maybeProviderOfLazy() {
      if (kind().equals(PROVIDER)) {
        Optional<KindAndType> providedKindAndType = from(type());
        if (providedKindAndType.isPresent()
            && providedKindAndType.get().kind().equals(LAZY)) {
          return Optional.of(new KindAndType(PROVIDER_OF_LAZY, providedKindAndType.get().type()));
        }
      }
      return Optional.empty();
    }
  }

  @CanIgnoreReturnValue
  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder kind(RequestKind kind);

    abstract Builder key(Key key);

    abstract Builder requestElement(Element element);

    abstract Builder isNullable(boolean isNullable);

    @CheckReturnValue
    abstract DependencyRequest build();
  }

  /**
   * Factory for {@link DependencyRequest}s.
   *
   * <p>Any factory method may throw {@link TypeNotPresentException} if a type is not available,
   * which may mean that the type will be generated in a later round of processing.
   */
  static final class Factory {
    private final KeyFactory keyFactory;

    Factory(KeyFactory keyFactory) {
      this.keyFactory = keyFactory;
    }

    ImmutableSet<DependencyRequest> forRequiredResolvedVariables(
        List<? extends VariableElement> variables, List<? extends TypeMirror> resolvedTypes) {
      checkState(resolvedTypes.size() == variables.size());
      ImmutableSet.Builder<DependencyRequest> builder = ImmutableSet.builder();
      for (int i = 0; i < variables.size(); i++) {
        builder.add(forRequiredResolvedVariable(variables.get(i), resolvedTypes.get(i)));
      }
      return builder.build();
    }

    /**
     * Creates synthetic dependency requests for each individual multibinding contribution in {@code
     * multibindingContributions}.
     */
    ImmutableSet<DependencyRequest> forMultibindingContributions(
        Key multibindingKey, Iterable<ContributionBinding> multibindingContributions) {
      ImmutableSet.Builder<DependencyRequest> requests = ImmutableSet.builder();
      for (ContributionBinding multibindingContribution : multibindingContributions) {
        requests.add(forMultibindingContribution(multibindingKey, multibindingContribution));
      }
      return requests.build();
    }

    /**
     * Creates a synthetic dependency request for one individual {@code multibindingContribution}.
     */
    private DependencyRequest forMultibindingContribution(
        Key multibindingKey, ContributionBinding multibindingContribution) {
      checkArgument(
          multibindingContribution.key().multibindingContributionIdentifier().isPresent(),
          "multibindingContribution's key must have a multibinding contribution identifier: %s",
          multibindingContribution);
      return DependencyRequest.builder()
          .kind(multibindingContributionRequestKind(multibindingKey, multibindingContribution))
          .key(multibindingContribution.key())
          .build();
    }

    // TODO(b/28555349): support PROVIDER_OF_LAZY here too
    private static final ImmutableSet<RequestKind> WRAPPING_MAP_VALUE_FRAMEWORK_TYPES =
        ImmutableSet.of(PROVIDER, PRODUCER);

    private RequestKind multibindingContributionRequestKind(
        Key multibindingKey, ContributionBinding multibindingContribution) {
      switch (multibindingContribution.contributionType()) {
        case MAP:
          MapType mapType = MapType.from(multibindingKey);
          for (RequestKind kind : WRAPPING_MAP_VALUE_FRAMEWORK_TYPES) {
            if (mapType.valuesAreTypeOf(frameworkClass(kind).get())) {
              return kind;
            }
          }
          // fall through
        case SET:
        case SET_VALUES:
          return INSTANCE;
        case UNIQUE:
          throw new IllegalArgumentException(
              "multibindingContribution must be a multibinding: " + multibindingContribution);
        default:
          throw new AssertionError(multibindingContribution.toString());
      }
    }

    DependencyRequest forRequiredResolvedVariable(
        VariableElement variableElement, TypeMirror resolvedType) {
      checkNotNull(variableElement);
      checkNotNull(resolvedType);
      Optional<AnnotationMirror> qualifier = InjectionAnnotations.getQualifier(variableElement);
      return newDependencyRequest(variableElement, resolvedType, qualifier);
    }

    DependencyRequest forComponentProvisionMethod(ExecutableElement provisionMethod,
        ExecutableType provisionMethodType) {
      checkNotNull(provisionMethod);
      checkNotNull(provisionMethodType);
      checkArgument(
          provisionMethod.getParameters().isEmpty(),
          "Component provision methods must be empty: %s",
          provisionMethod);
      Optional<AnnotationMirror> qualifier = InjectionAnnotations.getQualifier(provisionMethod);
      return newDependencyRequest(provisionMethod, provisionMethodType.getReturnType(), qualifier);
    }

    DependencyRequest forComponentProductionMethod(ExecutableElement productionMethod,
        ExecutableType productionMethodType) {
      checkNotNull(productionMethod);
      checkNotNull(productionMethodType);
      checkArgument(productionMethod.getParameters().isEmpty(),
          "Component production methods must be empty: %s", productionMethod);
      TypeMirror type = productionMethodType.getReturnType();
      Optional<AnnotationMirror> qualifier = InjectionAnnotations.getQualifier(productionMethod);
      // Only a component production method can be a request for a ListenableFuture, so we
      // special-case it here.
      if (isTypeOf(ListenableFuture.class, type)) {
        return DependencyRequest.builder()
            .kind(FUTURE)
            .key(keyFactory.forQualifiedType(
                qualifier, Iterables.getOnlyElement(((DeclaredType) type).getTypeArguments())))
            .requestElement(productionMethod)
            .build();
      } else {
        return newDependencyRequest(productionMethod, type, qualifier);
      }
    }

    DependencyRequest forComponentMembersInjectionMethod(ExecutableElement membersInjectionMethod,
        ExecutableType membersInjectionMethodType) {
      checkNotNull(membersInjectionMethod);
      checkNotNull(membersInjectionMethodType);
      Optional<AnnotationMirror> qualifier =
          InjectionAnnotations.getQualifier(membersInjectionMethod);
      checkArgument(!qualifier.isPresent());
      TypeMirror returnType = membersInjectionMethodType.getReturnType();
      TypeMirror membersInjectedType =
          MoreTypes.isType(returnType) && MoreTypes.isTypeOf(MembersInjector.class, returnType)
              ? getOnlyElement(MoreTypes.asDeclared(returnType).getTypeArguments())
              : getOnlyElement(membersInjectionMethodType.getParameterTypes());
      return DependencyRequest.builder()
          .kind(MEMBERS_INJECTION)
          .key(keyFactory.forMembersInjectedType(membersInjectedType))
          .requestElement(membersInjectionMethod)
          .build();
    }

    DependencyRequest forProductionImplementationExecutor() {
      Key key = keyFactory.forProductionImplementationExecutor();
      return DependencyRequest.builder()
          .kind(PROVIDER)
          .key(key)
          .requestElement(MoreTypes.asElement(key.type()))
          .build();
    }

    DependencyRequest forProductionComponentMonitor() {
      return DependencyRequest.builder()
          .kind(PROVIDER)
          .key(keyFactory.forProductionComponentMonitor())
          .build();
    }

    /**
     * Returns a synthetic request for the present value of an optional binding generated from a
     * {@link dagger.BindsOptionalOf} declaration.
     */
    DependencyRequest forSyntheticPresentOptionalBinding(Key requestKey, RequestKind kind) {
      Optional<Key> key = keyFactory.unwrapOptional(requestKey);
      checkArgument(key.isPresent(), "not a request for optional: %s", requestKey);
      return builder()
          .kind(kind)
          .key(key.get())
          .isNullable(
              allowsNull(
                  extractKindAndType(OptionalType.from(requestKey).valueType()).kind(),
                  Optional.empty()))
          .build();
    }

    private DependencyRequest newDependencyRequest(
        Element requestElement,
        TypeMirror type,
        Optional<AnnotationMirror> qualifier) {
      KindAndType kindAndType = extractKindAndType(type);
      return DependencyRequest.builder()
          .kind(kindAndType.kind())
          .key(keyFactory.forQualifiedType(qualifier, kindAndType.type()))
          .requestElement(requestElement)
          .isNullable(allowsNull(kindAndType.kind(), getNullableType(requestElement)))
          .build();
    }

    /**
     * Returns {@code true} if a given request element allows null values. {@link
     * RequestKind#INSTANCE} requests must be annotated with {@code @Nullable} in order to allow
     * null values. All other request kinds implicitly allow null values because they are are
     * wrapped inside {@link Provider}, {@link Lazy}, etc.
     */
    // TODO(sameb): should Produced/Producer always require non-nullable?
    private boolean allowsNull(RequestKind kind, Optional<DeclaredType> nullableType) {
      return kind.equals(INSTANCE) ? nullableType.isPresent() : true;
    }
  }
}
