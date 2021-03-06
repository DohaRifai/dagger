/*
 * Copyright (C) 2017 The Dagger Authors.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.model.RequestKind;

/** A factory of code expressions used to access a single request for a binding in a component. */
// TODO(user): Rename this to RequestExpression?
abstract class BindingExpression {
  private final ResolvedBindings resolvedBindings;
  private final RequestKind requestKind;

  BindingExpression(ResolvedBindings resolvedBindings, RequestKind requestKind) {
    this.resolvedBindings = checkNotNull(resolvedBindings);
    this.requestKind = checkNotNull(requestKind);
  }

  /** Returns the {@linkplain BindingKey} for this expression. */
  final BindingKey bindingKey() {
    return resolvedBindings.bindingKey();
  }

  /** Returns the {@linkplain RequestKind} handled by this expression. */
  final RequestKind requestKind() {
    return requestKind;
  }

  /** The binding this instance uses to fulfill requests. */
  final ResolvedBindings resolvedBindings() {
    return resolvedBindings;
  }

  /**
   * Returns an expression that evaluates to the value of a request based on the given requesting
   * class.
   *
   * @param requestingClass the class that will contain the expression
   */
  abstract Expression getDependencyExpression(ClassName requestingClass);

  /** Returns an expression for the implementation of a component method with the given request. */
  final CodeBlock getComponentMethodImplementation(
      ComponentMethodDescriptor componentMethod, ClassName requestingClass) {
    DependencyRequest request = componentMethod.dependencyRequest().get();
    checkArgument(request.bindingKey().equals(bindingKey()));
    checkArgument(request.kind().equals(requestKind()));
    return doGetComponentMethodImplementation(componentMethod, requestingClass);
  }

  /**
   * Returns an expression for the implementation of a component method with the given request.
   *
   * <p>This method is called only if {@code componentMethod}'s request key and kind matches this
   * binding expression's.
   */
  protected CodeBlock doGetComponentMethodImplementation(
      ComponentMethodDescriptor componentMethod, ClassName requestingClass) {
    // By default, just delegate to #getDependencyExpression().
    return CodeBlock.of("return $L;", getDependencyExpression(requestingClass).codeBlock());
  }
}
