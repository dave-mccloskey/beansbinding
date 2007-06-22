/*
 * Copyright (C) 2006-2007 Sun Microsystems, Inc. All rights reserved. Use is
 * subject to license terms.
 */

package javax.beans.binding;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.beans.binding.ext.BindingTarget;
import javax.beans.binding.ext.BindingTargetProvider;
import javax.beans.binding.ext.PropertyDelegateFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.el.ELContext;
import javax.el.Expression;
import static javax.el.Expression.Result.Type.*;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * {@code Binding} represents a binding between one or more properties of a
 * source object and a single property of a target object. Once bound, a binding
 * keeps both ends of the binding in sync as specified by the binding's update
 * strategy.
 * <p>
 * The following example illustrates binding the {@code "name"} property
 * of a {@code Customer} to the {@code "text"} property of a {@code JTextField}:
 * <pre>
 * class Customer {
 *     private String name;
 *
 *     public Customer(String name) { this.name = name; }
 *
 *     public String getName() { return name; }
 *
 *     public void setName(String name) {
 *         String old = this.name;
 *         this.name = name;
 *         firePropertyChange("name", old, name);
 *     }
 * }
 *
 * Customer customer = new Customer("Duke");
 * JTextField nameField = new JTextField();
 * Binding customerBinding = new Binding(customer, "${name}", nameField, "text");
 * customerBinding.bind();
 * </pre>
 * <p>
 * Notice that this binding's source string is prefixed with '$' and is wrapped in
 * curly brackets; the source of a binding is specified using the EL expression
 * language syntax, and is resolved using EL. Dot-separation is used to identify
 * the path to a property through multiple levels. For example,
 * {@code "manager.firstName"} is equivalent to
 * {@code getManager().getFirstName()}. Using EL for the source provides more
 * power than using simple reflection. You can reference multiple properties
 * using an expression (for example: {@code "${lastName}, ${firstName}"})
 * and even use functions. For the purposes of this documentation, we'll refer
 * to this as the source "property". Please refer to the EL documentation for
 * more information on using EL.
 * <p>
 * The target of a binding uses simple dot-separation syntax to identify the path
 * to a property, and is resolved using reflection. To resolve the target property,
 * {@code Binding} makes use of {@code PropertyResolver}. Refer to its
 * documentation for more information on the syntax.
 * <p>
 * Note that if the source expression represents more than just the path to
 * a single property, then the binding must be one-way only. It's not possible
 * to reverse the expression in order to affect multiple properties.
 * <p>
 * Often it is not possible to fully evaluate a path to a property; this is
 * referred to as an incomplete path. For example, when
 * evaluating the path {@code "manager.firstName"}, if {@code source.getManager()}
 * returns null (and therefore its "firstName" property can't be reached), then
 * that path is incomplete. {@code Binding} provides for this by way of the
 * {@code setValueForIncompleteSourcePath} and
 * {@code setValueForIncompleteTargetPath} methods that allow you to specify the
 * value to use when an incomplete path is encountered.
 * <p>
 * If a property changes such that a source expression or target path contain an
 * incomplete path, and the corresponding value for the incomplete path property
 * is {@code non-null}, then that value is applied to the opposite object's property.
 * Otherwise the opposite property is not updated. For example, if the source
 * expression contains the path {@code "manager.firstName"}, the target path is
 * {@code "text"}, and the {@code "manager"} property of the source becomes
 * {@code null}, then if {@code getValueForIncompleteSourcePath} is {@code non-null},
 * that value is set as the target's {@code "text"} property.
 * Otherwise the target is not updated.
 * <p>
 * To keep the properties of the two objects in sync, listeners are installed on
 * all objects along the paths. Any time an object along a path changes, the
 * opposite property is updated, if appropriate. For example, if the source
 * expression contains the path {@code "${manager.firstName}"},
 * and either the {@code "manager"} or
 * {@code "firstName"} property of the source object changes, then the target
 * property is updated. There is one exception to this: when initially bound,
 * if the target path is incomplete, then changes in the target path only update
 * the source if {@code getValueForIncompleteTargetPath} returns {@code non-null}.
 * Otherwise, once the target path becomes complete its value is reset from that
 * of the source.
 * <p>
 * Data flowing from the source to the target, and from the target to the
 * source, may pass through a {@code BindingConverter}, which is used to convert
 * the data in some way; typically between types. For
 * example, you may want to bind the background color of a component to a string
 * property, and set a {@code BindingConverter} that can convert between
 * {@code String} and {@code Color}. {@code Binding} automatically converts
 * between some of the known types if no converter is specified.
 * <p>
 * If a {@code BindingValidator} is specified, all changes from the
 * target are passed to the {@code BindingValidator}. The {@code
 * BindingValidator} is used for testing the validity of a change, as
 * well as for specifying if the change should be propagated back to
 * the source.
 * <p>
 * Bindings need not automatically be two-way: {@code Binding} allows for an
 * update strategy to be specified for the binding, which dictates how the
 * source and target properties are kept in sync. A typical use case is to have
 * a set of one-way bindings from source to target where the target values are
 * validated as a set before explicitly committing the values back to the source.
 * <p>
 * The source and target of a binding are typically {@code non-null} values,
 * since the binding needs objects on which to operate. An exception to this
 * is for children bindings, in which case the parent binding supplies the two
 * endpoints.
 * <p>
 * While a {@code Binding} is bound, it can not be mutated. All setter methods
 * of this class throw an {@code IllegalStateException} if invoked on a bound
 * {@code Binding}.
 *
 * @see javax.swing.binding.SwingBindingSupport
 *
 * @author Scott Violet
 * @author Shannon Hickey
 * @author Jan Stola
 */
public class Binding {
    //
    // In general the following ordering is attempted to be used:
    //   1. change received
    //   2. update binding target (unbind/bind if changed)
    //   3. update state
    //   4. notify validation listener
    //   5. notify BindingContext
    //   6. remove the binding, if necessary
    //

    /**
     * Enumeration of the possible ways that the source and target can be kept
     * in sync.
     */
    public enum UpdateStrategy {
        /**
         * Enumeration value indicating that the target should be kept in sync
         * with the source. Changes to the target are not propaged back to the
         * source. A one-way binding.
         */
        READ_FROM_SOURCE,
        
        /**
         * Enumeration value indicating that the target should only be set from
         * the source value once, when the {@code Binding} is created.
         * Subsequent changes to the source or target are not propagated to
         * the other.
         */
        READ_ONCE,
        
        /**
         * Enumeration value indicating that the source and target should be
         * kept in sync at all times. This is the default update strategy
         * for {@code Binding}.
         */
        READ_WRITE
    }

    /**
     * Enumeration of the possible states that the source and target may be in.
     */
    public enum ValueState {
        /**
         * Enumeration value indicating that the expression/path contains
         * elements that are not fully reachable. For example, if the source
         * expression contains {@code "manager.firstName"} and the {@code "manager"}
         * property is {@code null}, then the {@code "firstName"} property can
         * not be evaluated, and the source state is set to
         * {@code INCOMPLETE_PATH}.
         */
        INCOMPLETE_PATH,
        
        /**
         * Enumeration value indicating that the value has not been
         * committed to the the opposite end. For example, if the
         * update strategy is {@code READ_ONCE} and the source value
         * changes, then the source value state is set to {@code UNCOMMITTED}.
         */
        UNCOMMITTED,
        
        /**
         * Enumeration value indicating that the value is invalid. This
         * occurs if the {@code BindingValidator} indicates an invalid
         * value should be left in the target, or if the {@code BindingConverter}
         * throws a {@code ClassCastException} or {@code IllegalArgumentException}
         * on conversion.
         */
        INVALID,
        
        /**
         * Enumeration value indicating that the value is valid and in sync with
         * the other object.
         */
        VALID
    }

    private String name;

    private List<BindingListener> bindingListeners;
    private Map<ParameterKey<?>, Object> parameters;

    private Object source;
    private String sourceExpression;
    private Object target;
    private String targetPath;
    private Object tmpSource;
    private Object tmpTarget;
    private String tmpTargetPath;
    
    private BindingValidator validator;
    private BindingConverter converter;
    private Object nullSourceValue;
    private Object nullTargetValue;
    private Object incompleteSourcePathValue;
    private Object incompleteTargetPathValue;
    private UpdateStrategy updateStrategy;
    
    private ListCondenser condenser;

    private BindingContext context;
    private boolean bound;
    private ELPropertyResolver sourceResolver;
    private PropertyResolver targetResolver;
    private boolean changingValue;
    private ValueState targetValueState;
    private ValueState sourceValueState;
    private List<Binding> childBindings;
    private Map<String, Binding> namedChildren;
    private boolean keepUncommitted;
    private BindingTarget bindingTarget;
    private BindingTargetProvider bindingProvider;
    private Binding parentBinding;
    private boolean unbindOnCommit;
    private BindingController bindingController;
    private boolean completeTargetPath;
    private Object lastTarget;

    /**
     * Creates a {@code Binding}.
     */
    public Binding() {
        this(null, null);
    }
    
    /**
     * Creates a {@code Binding}. See 
     * {@link javax.swing.binding.SwingBindingSupport} for examples.
     *
     * @param sourceExpression El expression specifying the "property" of the source
     * @param targetPath path to the property of the target
     */
    public Binding(String sourceExpression, String targetPath) {
        this(null, null, sourceExpression, null, targetPath);
    }

    /**
     * Creates a {@code Binding}. See 
     * {@link javax.swing.binding.SwingBindingSupport} for examples.
     *
     * @param name a name for the binding
     * @param sourceExpression El expression specifying the "property" of the source
     * @param targetPath path to the property of the target
     *
     */
    public Binding(String name, String sourceExpression, String targetPath) {
        this(name, null, sourceExpression, null, targetPath);
    }

    /**
     * Creates a {@code Binding}. See 
     * {@link javax.swing.binding.SwingBindingSupport} for examples.
     *
     * @param source the source of the binding
     * @param sourceExpression El expression specifying the "property" of the source
     * @param target the target of the binding
     * @param targetPath path to the property of the target
     */
    public Binding(Object source, String sourceExpression,
                   Object target, String targetPath) {

        this(null, source, sourceExpression, target, targetPath);
    }

    /**
     * Creates a {@code Binding}. See 
     * {@link javax.swing.binding.SwingBindingSupport} for examples.
     *
     * @param name a name for the binding
     * @param source the source of the binding
     * @param sourceExpression El expression specifying the "property" of the source
     * @param target the target of the binding
     * @param targetPath path to the property of the target
     */
    public Binding(String name,
                   Object source, String sourceExpression,
                   Object target, String targetPath) {

        setName(name);
        setUpdateStrategy(updateStrategy.READ_WRITE);
        setSource(source);
        setSourceExpression(sourceExpression);
        setTarget(target);
        setTargetPath(targetPath);
    }

    /**
     * Sets a name for the binding. This is useful to fetch bindings by name.
     * May be {@code null} to indicate that the binding doesn't have a name.
     *
     * @param name a name for the binding
     * @throws IllegalStateException if bound
     * @throws IllegalArgumentException if this binding belongs to a
     *         {@code BindingContext} or a parent binding that already contains
     *         a binding with the given name
     */
    public final void setName(String name) {
        throwIfBound();

        if (this.name == name || (this.name != null && this.name.equals(name))) {
            return;
        }

        // at first, this implementation seems a little over-complex,
        // but the goal is to preserve the integrity of the object,
        // only making changes if we're not going to throw an exception

        if (parentBinding != null) {
            if (name != null && parentBinding.getChildBinding(name) != null) {
                throw new IllegalArgumentException("Sibling exists with same name.");
            }
        }

        if (context != null) {
            if (name != null && context.getBinding(name) != null) {
                throw new IllegalArgumentException("BindingContext contains binding with same name.");
            }
        }

        if (parentBinding != null) {
            if (this.name != null) {
                assert parentBinding.namedChildren != null;
                parentBinding.namedChildren.remove(this.name);
            }

            if (name != null) {
                parentBinding.putNamedChild(name, this);
            }
        }

        if (context != null) {
            if (this.name != null) {
                assert context.namedBindings != null;
                context.namedBindings.remove(this.name);
            }

            if (name != null) {
                context.putNamed(name, this);
            }
        }

        this.name = name;
    }

    private void putNamedChild(String name, Binding binding) {
        if (namedChildren == null) {
            namedChildren = new HashMap<String, Binding>();
        }
        
        namedChildren.put(name, binding);
    }
    
    /**
     * Returns the binding's name.
     *
     * @return the binding's name, or {@code null}
     */
    public final String getName() {
        return name;
    }

    /**
     * Sets the source of the binding.
     *
     * @param source the source of the binding
     * @throws IllegalStateException if bound
     */
    public final void setSource(Object source) {
        throwIfBound();
        this.source = source;
    }
    
    /**
     * Returns the source of the binding.
     *
     * @return the source of the binding
     */
    public final Object getSource() {
        return source;
    }
    
    private Object getSource0() {
        return (tmpSource != null) ? tmpSource : source;
    }
    
    /**
     * Sets the El expression indicating the "property" to bind to
     * with respect to the source object.
     *
     * @param expression EL expression specifying the source "property"
     * @throws IllegalStateException if bound
     */
    public final void setSourceExpression(String expression) {
        throwIfBound();
        this.sourceExpression = expression;
    }
    
    /**
     * Returns the EL expression indicating the "property" to bind to
     * with respect to the source object.
     *
     * @return EL expression specifying the source "property"
     */
    public final String getSourceExpression() {
        return sourceExpression;
    }
    
    /**
     * Sets the target of the binding.
     *
     * @param target the target of the binding
     * @throws IllegalStateException if bound
     */
    public final void setTarget(Object target) {
        throwIfBound();
        this.target = target;
    }
    
    /**
     * Returns the target of the binding.
     *
     * @return the target of the binding
     */
    public final Object getTarget() {
        return target;
    }
    
    private Object getTarget0() {
        return (tmpTarget != null) ? tmpTarget : target;
    }

    /**
     * Sets the path to the property of the target to bind to.
     *
     * @param path path to the property of the target to bind to
     * @throws IllegalStateException if bound
     * @throws IllegalArgumentException if path is non-null and empty
     */
    public final void setTargetPath(String path) {
        throwIfBound();
        if (path != null && path.length() == 0) {
            throw new IllegalArgumentException("Target path must be non-empty");
        }
        this.targetPath = path;
    }
    
    /**
     * Returns the path to the property of the target to bind to.
     *
     * @return path to the property of the target to bind to
     */
    public final String getTargetPath() {
        return targetPath;
    }
    
    private String getTargetPath0() {
        return (tmpTargetPath != null) ? tmpTargetPath : targetPath;
    }
    
    /**
     * Sets the {@code BindingValidator} used to validate changes originating
     * from the target.
     *
     * @param validator the {@code BindingValidator}
     * @throws IllegalStateException if bound
     */
    public final void setValidator(BindingValidator validator) {
        throwIfBound();
        this.validator = validator;
    }

    /**
     * Returns the {@code BindingValidator} used to validate changes
     * originating from the target.
     *
     * @return the validator
     */
    public final BindingValidator getValidator() {
        return validator;
    }
    
    /**
     * Sets the {@code BindingConverter} used to convert values.
     * {@code BindingConverter} is only used to convert {@code non-null}
     * values.
     * <p>
     * {@code Binding} automatically converts between some of the known types
     * if no converter is specified.
     *
     * @param converter the {@code BindingConverter}
     * @throws IllegalStateException if bound
     */
    public final void setConverter(BindingConverter converter) {
        throwIfBound();
        this.converter = converter;
    }
    
    /**
     * Returns the {@code BindingConverter} used to convert values.
     *
     * @return the {@code BindingConverter}
     */
    public final BindingConverter getConverter() {
        return converter;
    }
    
    /**
     * Sets the value to use if the source contains a path that can not be
     * completely evaluated.
     *
     * @param value the value
     */
    public final void setValueForIncompleteSourcePath(Object value) {
        throwIfBound();
        incompleteSourcePathValue = value;
    }
    
    /**
     * Returns the value to use if the source contains a path that can not be
     * completely evaluated.
     *
     * @return the value
     */
    public final Object getValueForIncompleteSourcePath() {
        return incompleteSourcePathValue;
    }
    
    /**
     * Sets the value to use if the target path can not be completely
     * evaluated.
     *
     * @param value the value
     */
    public final void setValueForIncompleteTargetPath(Object value) {
        throwIfBound();
        incompleteTargetPathValue = value;
    }
    
    /**
     * Returns the value to use if the target path can not be completely
     * evaluated.
     *
     * @return the value
     */
    public final Object getValueForIncompleteTargetPath() {
        return incompleteTargetPathValue;
    }
    
    /**
     * Sets the value to use if the source "property" value evaluates to
     * {@code null}.
     *
     * @param value the value to use if the source "property" value
     *        is {@code null}
     * @throws IllegalStateException if bound
     */
    public final void setNullSourceValue(Object value) {
        throwIfBound();
        this.nullSourceValue = value;
    }
    
    /**
     * Returns the value to use if the source "property" value evaluates to
     * {@code null}.
     *
     * @return the value to use if the source "property" value is {@code null}
     */
    public final Object getNullSourceValue() {
        return nullSourceValue;
    }
    
    /**
     * Sets the value to use if the value of the property of the target is
     * {@code null}.
     *
     * @param value the value to use if the value of the property of the
     *        target is {@code null}
     * @throws IllegalStateException if bound
     */
    public final void setNullTargetValue(Object value) {
        throwIfBound();
        this.nullTargetValue = value;
    }
    
    /**
     * Returns the value to use if the value of the property of the target is
     * {@code null}.
     *
     * @return the value to use if the value of the property of the
     *         target is {@code null}
     */
    public final Object getNullTargetValue() {
        return nullTargetValue;
    }
    
    /**
     * Sets the update strategy for the binding. The default is
     * {@code READ_WRITE}.
     *
     * @param strategy the update strategy
     * @throws IllegalArgumentException if {@code strategy} is {@code null}
     * @throws IllegalStateException if bound
     */
    public final void setUpdateStrategy(UpdateStrategy strategy) {
        throwIfBound();
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "UpdateStrategy must be non-null");
        }
        this.updateStrategy = strategy;
    }

    /**
     * Returns the update strategy.
     *
     * @return update strategy
     */
    public final UpdateStrategy getUpdateStrategy() {
        return updateStrategy;
    }
    
    /**
     * Sets the {@code ListCondenser} that is used to condense a list into a
     * single value.
     *
     * @param condenser the condenser
     */
    public final void setListCondenser(ListCondenser condenser) {
        throwIfBound();
        this.condenser = condenser;
    }
    
    /**
     * Returns the {@code ListCondenser} that is used to condense a list into
     * a single value.
     *
     * @return the condenser
     */
    public final ListCondenser getListCondenser() {
        return condenser;
    }

    /**
     * Puts or removes a parameter for the binding. Putting the value for a key
     * with an existing value replaces the value associated with that key.
     * Putting a value of {@code null} removes any existing value for the key.
     * <p>
     * This method is used to specify target specific parameters. For
     * example, the following specifies that the "text" property of a
     * {@code JTextComponent} should change as you type:
     * <pre>
     *   binding.setValue(SwingBindingSupport.TextChangeStrategyParameter,
     *                    TextChangeStrategy.CHANGE_ON_TYPE);
     * </pre>
     * Returns {@code this} to allow for chaining of method calls.
     *
     * @param key the key identifying the parameter to put
     * @param value the value to set for the parameter, or {@code null}
     *        to remove any value for the key
     * @return {@code this}
     *
     * @throws ClassCastException if {@code value} is not of the type defined
     *         by {@code key}
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalStateException if bound
     */
    public final <T> Binding putParameter(ParameterKey<T> key, T value) {
        throwIfBound();

        if (value == null) {
            if (parameters != null) {
                parameters.remove(key);
            }
        } else {
            if (parameters == null) {
                parameters = new HashMap<ParameterKey<?>,Object>(1);
            }
            parameters.put(key, value);
        }
        
        return this;
    }

    /**
     * Returns the value for the parameter specified by the key.
     *
     * @param key the key to obtain the value for
     * @param defaultValue the value to return if the binding has no value
     *        for the given key
     * @return the value for the specified key
     *
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public final <T> T getValue(ParameterKey<T> key, T defaultValue) {
        if (parameters == null) {
            return defaultValue;
        }
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        return (T)value;
    }

    /**
     * Convenience method that throws an exception if bound. Methods that
     * set a property should invoke this before changing the property.
     *
     * @throws IllegalStateException if bound
     */
    final void throwIfBound() {
        if (isBound()) {
            throw new IllegalStateException(
                    "Can not change Binding once bound");
        }
    }

    /**
     * Returns the {@code BindingConverter} used to convert the source value to the
     * target value.
     *
     * @param sourceType the type of the source property
     * @param sourceValue the value of the source
     * @param targetType the type of the target property
     */
    public BindingConverter getSourceToTargetConverter(Class<?> sourceType,
            Object sourceValue, Class<?> targetType) {
        BindingConverter converter = getConverter();
        if (converter == null) {
            converter = BindingConverter.getConverter(sourceType, targetType);
        }
        return converter;
    }
    
    /**
     * Returns the BindingConverter used to convert the target value to
     * the source value.
     *
     * @param targetType the type of the target property
     * @param targetValue the value of the target
     * @param sourceType the type of the source property
     */
    public BindingConverter getTargetToSourceConverter(Class<?> targetType,
            Object targetValue, Class<?> sourceType) {
        BindingConverter converter = getConverter();
        if (converter == null) {
            converter = BindingConverter.getConverter(sourceType, targetType);
        }
        return converter;
    }

    private void setParentBinding(Binding binding) {
        // caller to do error checking first
        this.parentBinding = binding;
    }

    public final Binding getParentBinding() {
        return parentBinding;
    }

    final void setContext(BindingContext context) {
        // caller to do error checking first
        this.context = context;
    }

    /**
     * Returns the {@code BindingContext} this {@code Binding} is
     * contained in.
     *
     * @return the {@code BindingContext}.
     */
    public final BindingContext getContext() {
        return context;
    }

    /**
     * Returns {@code true} if currently bound.
     *
     * @return {@code true} if currently bound
     */
    public final boolean isBound() {
        return bound;
    }
    
    /**
     * Realizes this {@code Binding}.
     *
     * @throws IllegalStateException if already bound, or
     *         the source or target is {@code null}
     * @throws PropertyResolverException if {@code PropertyResolver} throws an
     *         exception; refer to {@code PropertyResolver} for the conditions
     *         under which an exception is thrown
     * @throws IllegalArgumentException if the child bindings do not contain
     *         the expected information for the target
     */
    public final void bind() {
        throwIfBound();
        if (getSource0() == null || getTarget0() == null) {
            throw new IllegalStateException(
                    "Source and target must be non-null");
        }
        completeTargetPath = false;
        bound = true;
        BindingContext context = getContext();
        if (context != null) {
            context.bindingBecameBound(this);
        }
        
        // Assume initially valid, this will get straightened out in
        // setTargetValueFromSourceValue
        targetValueState = ValueState.VALID;
        
        // Create PropertyResolvers for source and target
        sourceResolver = new ELPropertyResolver(getELContext(), 
                getSource0(), getSourceExpression());
        sourceResolver.bind();
        if (sourceResolver.getEvaluationResultType() == INCOMPLETE_PATH) {
            sourceValueState = ValueState.INCOMPLETE_PATH;
        } else {
            sourceValueState = ValueState.VALID;
        }
        // Set the delegate after invoking bind to avoid notification
        sourceResolver.setDelegate(new ELPropertyResolverDelegate());
        targetResolver = PropertyResolver.createPropertyResolver(
                getTarget0(), getTargetPath0());
        targetResolver.bind();
        // Set the delegate after invoking bind to avoid notification
        targetResolver.setDelegate(new PropertyResolverDelegate());
        
        updateBindingTargetIfNecessary();

        // Notify target of value
        setTargetValueFromSourceValue();
    }
    
    /**
     * Unrealizes this binding.
     *
     * @throws IllegalStateException if binding is not bound
     * @throws PropertyResolverException if {@code PropertyResolver} throws an
     *         exception; refer to {@code PropertyResolver} for the conditions
     *         under which an exception is thrown
     */
    public final void unbind() {
        throwIfNotBound();

        unbindBindingTarget();
        bound = false;
        sourceResolver.unbind();
        targetResolver.unbind();
        sourceResolver = null;
        targetResolver = null;
        sourceValueState = targetValueState = null;
        
        // Unbind any children bindings
        if (childBindings != null) {
            for (Binding binding : childBindings) {
                if (binding.isBound()) {
                    binding.unbind();
                }
            }
        }
        
        BindingContext context = getContext();
        if (context != null) {
            context.bindingBecameUnbound(this);
        }
    }
    
    /**
     * Sets the property of the source from that of the target.
     *
     * @throws PropertyResolverException if {@code PropertyResolver} throws an
     *         exception; refer to {@code PropertyResolver} for the conditions
     *         under which an exception is thrown
     * @throws IllegalStateException if not bound
     */
    public final void setSourceValueFromTargetValue() {
        throwIfNotBound();
        targetToSource();
    }
    
    /**
     * Sets the property of the target from that of the source.
     *
     * @throws PropertyResolverException
     * @throws IllegalStateException if not bound
     */
    public final void setTargetValueFromSourceValue() {
        throwIfNotBound();
        sourceToTarget();
    }
    
    /**
     * Adds a {@code BindingListener} to this {@code Binding}.
     *
     * @param listener the {@code BindingListener} to add
     */
    public void addBindingListener(BindingListener listener) {
        if (bindingListeners == null) {
            bindingListeners = new CopyOnWriteArrayList<BindingListener>();
        }
        bindingListeners.add(listener);
    }
    
    /**
     * Removes a {@code BindingListener} from this {@code Binding}.
     *
     * @param listener the {@code BindingListener} to remove
     */
    public void removeBindingListener(BindingListener listener) {
        if (bindingListeners != null) {
            bindingListeners.remove(listener);
        }
    }
    
    private void updateBindingTargetIfNecessary() {
        boolean completeTargetPath = targetResolver.hasAllPathValues();
        Object newTarget = null;
        this.completeTargetPath = completeTargetPath;
        if (completeTargetPath) {
            newTarget = targetResolver.getLastSource();
        }
        if (lastTarget != newTarget) {
            unbindBindingTarget();
            bindBindingTarget(newTarget);
        }
    }

    // Invoked when either the source value has changed, or one of the elements
    // along the source path has changed.
    private void sourceChanged() throws PropertyResolverException {
        if (!changingValue) {
            if (getUpdateStrategy() != UpdateStrategy.READ_ONCE) {
                sourceToTarget();
            } else {
                ValueState[] oldState = getValueState();
                if (sourceResolver.getEvaluationResultType() != INCOMPLETE_PATH) {
                    setSourceValueState(ValueState.UNCOMMITTED);
                } else {
                    setSourceValueState(ValueState.INCOMPLETE_PATH);
                }
                notifyBindingContextIfNecessary(oldState, null);
            }
        }
    }
    
    // Invoked when either the target value has changed, or one of the elements
    // along the target path has changed.
    private void targetChanged() throws PropertyResolverException {
        if (!changingValue) {
            boolean wasCompleteTargetPath = this.completeTargetPath;
            boolean completeTargetPath = targetResolver.hasAllPathValues();
            Object newTarget = null;
            this.completeTargetPath = completeTargetPath;
            if (completeTargetPath) {
                newTarget = targetResolver.getLastSource();
            }
            if (lastTarget != newTarget) {
                unbindBindingTarget();
                bindBindingTarget(newTarget);
            }
            if (!wasCompleteTargetPath && completeTargetPath &&
                    getUpdateStrategy() == updateStrategy.READ_WRITE) {
                sourceToTarget();
            } else if (getUpdateStrategy() == UpdateStrategy.READ_WRITE) {
                targetToSource();
                targetEdited();
            } else {
                ValueState[] oldState = getValueState();
                if (completeTargetPath) {
                    targetEdited();
                    setTargetValueState(ValueState.UNCOMMITTED);
                } else {
                    setTargetValueState(ValueState.INCOMPLETE_PATH);
                }
                notifyBindingContextIfNecessary(oldState, null);
                unbindIfNecessary();
            }
        }
    }
    
    private void unbindBindingTarget() {
        if (bindingTarget != null) {
            bindingTarget.unbind(getBindingController(), getTargetProperty());
            bindingTarget = null;
            bindingProvider = null;
            lastTarget = null;
        }
    }
    
    private void bindBindingTarget(Object target) {
        this.lastTarget = target;
        if (lastTarget != null) {
            String lastProperty = getTargetProperty();
            Object delegate = PropertyDelegateFactory.
                    getPropertyDelegate(lastTarget, lastProperty);
            if (delegate != null) {
                if (delegate instanceof BindingTargetProvider) {
                    bindingProvider = (BindingTargetProvider)delegate;
                }
            } else if (lastTarget instanceof BindingTargetProvider) {
                bindingProvider = (BindingTargetProvider)lastTarget;
            }
            if (bindingProvider != null) {
                bindingTarget = bindingProvider.
                        createBindingTarget(lastProperty);
                if (bindingTarget != null) {
                    bindingTarget.bind(getBindingController(), lastProperty);
                } else {
                    bindingProvider = null;
                }
            }
        }
    }

    private void targetToSource() throws PropertyResolverException {
        ValueState[] state = getValueState();
        ValueState sourceState = null;
        ValueState targetState = null;
        Exception conversionException = null;
        ValidationResult validationResult = null;
        if (sourceResolver.getEvaluationResultType() != INCOMPLETE_PATH) {
            Object newValue;
            boolean setValue = true;
            sourceState = getSourceValueState();
            if (targetResolver.hasAllPathValues()) {
                boolean converted = false;
                newValue = null;
                try {
                    newValue = getValueForSource();
                    converted = true;
                } catch (ClassCastException cce) {
                    conversionException = cce;
                } catch (IllegalArgumentException iae) {
                    conversionException = iae;
                }
                if (!converted) {
                    targetState = ValueState.INVALID;
                    setValue = false;
                } else {
                    // Conversion was successful, if there's a validator,
                    // run it
                    validationResult = validate(newValue);
                    if (validationResult != null) {
                        setValue = false;
                        switch (validationResult.getType()) {
                            case DO_NOTHING:
                                // Value is bogus and we shouldn't propagate to
                                // source.
                                targetState = ValueState.INVALID;
                                break;
                            case SET_TARGET_FROM_SOURCE:
                                // Revert target from source.
                                converted = false;
                                newValue = null;
                                try {
                                    newValue = getValueForTarget();
                                    converted = true;
                                } catch (ClassCastException cce) {
                                    conversionException = cce;
                                } catch (IllegalArgumentException iae) {
                                    conversionException = iae;
                                }
                                if (converted) {
                                    changingValue = true;
                                    newValue = coerceValue(
                                            targetResolver.getTypeOfLastProperty(),
                                            newValue);
                                    targetResolver.setValueOfLastProperty(newValue);
                                    changingValue = false;
                                    targetState = ValueState.VALID;
                                    sourceState = ValueState.VALID;
                                } else {
                                    sourceState = ValueState.INVALID;
                                    targetState = ValueState.INVALID;
                                }
                                break;
                        }
                    } else {
                        // Validation succeeded, or no validator.
                        sourceState = ValueState.VALID;
                        targetState = ValueState.VALID;
                    }
                }
            } else {
                newValue = getValueForIncompleteTargetPath();
                if (newValue == null) {
                    setValue = false;
                } else {
                    sourceState = ValueState.VALID;
                }
                targetState = ValueState.INCOMPLETE_PATH;
            }
            if (setValue) {
                changingValue = true;
                sourceResolver.setValueOfLastProperty(coerceValue(
                        sourceResolver.getTypeOfLastProperty(), newValue));
                changingValue = false;
            }
        } else {
            sourceState = ValueState.INCOMPLETE_PATH;
            if (targetResolver.hasAllPathValues()) {
                targetState = ValueState.UNCOMMITTED;
            } else {
                targetState = ValueState.INCOMPLETE_PATH;
            }
        }
        setSourceValueState(sourceState);
        setTargetValueState(targetState);
        if (validationResult != null) {
            if (bindingListeners != null) {
                for (BindingListener listener : bindingListeners) {
                    listener.validationFailed(this, validationResult);
                }
            }
            if (context != null) {
                context.notifyValidationListeners(this, validationResult);
            }
        }
        notifyBindingContextIfNecessary(state, conversionException);
        unbindIfNecessary();
    }
            
    private void sourceToTarget() throws PropertyResolverException {
        ValueState[] state = getValueState();
        Exception converterException = null;
        ValueState targetState;
        ValueState sourceState;
        if (targetResolver.hasAllPathValues()) {
            completeTargetPath = true;
            Object newValue;
            if (sourceResolver.getEvaluationResultType() != INCOMPLETE_PATH) {
                boolean converted = false;
                // Avoids compiler warning
                newValue = null;
                try {
                    newValue = getValueForTarget();
                    converted = true;
                } catch (ClassCastException cce) {
                    converterException = cce;
                } catch (IllegalArgumentException iae) {
                    converterException = iae;
                }
                if (converted) {
                    sourceState = ValueState.VALID;
                    targetState = ValueState.VALID;
                    changingValue = true;
                    targetResolver.setValueOfLastProperty(coerceValue(
                            targetResolver.getTypeOfLastProperty(), newValue));
                    changingValue = false;
                } else {
                    sourceState = ValueState.INVALID;
                    targetState = getTargetValueState();
                }
            } else {
                newValue = getValueForIncompleteSourcePath();
                if (newValue != null) {
                    // If the value for incomplete source path is null, we have
                    // way of knowing if the types match. As such, we only set
                    // the value in this case if an explicit value has been
                    // set.
                    changingValue = true;
                    targetResolver.setValueOfLastProperty(newValue);
                    changingValue = false;
                }
                sourceState = ValueState.INCOMPLETE_PATH;
                targetState = ValueState.VALID;
            }
        } else {
            targetState = ValueState.INCOMPLETE_PATH;
            if (sourceResolver.getEvaluationResultType() != INCOMPLETE_PATH) {
                sourceState = ValueState.UNCOMMITTED;
            } else {
                sourceState = ValueState.INCOMPLETE_PATH;
            }
            completeTargetPath = false;
        }
        setTargetValueState(targetState);
        setSourceValueState(sourceState);
        notifyBindingContextIfNecessary(state, converterException);
        unbindIfNecessary();
    }
    
    private BindingValidator getValidator0() {
        BindingValidator validator = getValidator();
        if (validator == null) {
            BindingContext context = getContext();
            if (context != null) {
                validator = context.getValidator(this);
            }
        }
        return validator;
    }
    
    // Validates the value from the target. A return value of true indicates
    // the value is valid, and should be sent to the source. A value of false
    // indicates the value should not be propagated to the source.
    private ValidationResult validate(Object value) throws PropertyResolverException {
        BindingValidator validator = getValidator0();
        if (validator != null) {
            return validator.validate(this, value);
        }
        return null;
    }

    /**
     * Returns the value from the source that should be set on the target.
     *
     * @throws ClassCastException if converter throws
     * @throws IllegalArgumentException if converter throws
     */
    private Object getValueForTarget() throws PropertyResolverException {
        assert (sourceResolver.getEvaluationResultType() != INCOMPLETE_PATH && 
                targetResolver.hasAllPathValues());
        return getValueForTarget(sourceResolver, 
                targetResolver.getTypeOfLastProperty());
    }
    
    private Object getValueForTarget(ELPropertyResolver resolver,
            Class<?> targetType) throws PropertyResolverException {
        Object value;
        if (!resolver.isBound()) {
            Expression.Result result = resolver.evaluate();
            value = result.getResult();
            // PENDING: this needs to deal with multi-values, and all sorts of
            // other cases.
        } else {
            value = resolver.getValueOfLastProperty();
            if (resolver.getEvaluationResultType() != SINGLE_VALUE) {
                // Multiple values have been specified
                ListCondenser condenser = getListCondenser();
                if (condenser != null) {
                    List<?> values = (List<?>)value;
                    List<Object> convertedValues = new ArrayList<Object>(
                            values.size());
                    Class<?> sourceType = resolver.getTypeOfLastProperty();
                    for (int i = 0; i < values.size(); i++) {
                        Object e = values.get(i);
                        if (e == null) {
                            convertedValues.add(getNullSourceValue());
                        } else {
                            BindingConverter converter = getSourceToTargetConverter(
                                    sourceType, e, targetType);
                            if (converter != null) {
                                e = converter.sourceToTarget(e);
                            }
                            convertedValues.add(e);
                        }
                    }
                    value = condenser.condense(convertedValues);
                    return value;
                } else {
                    // Multiple values with no selector, choose the first element.
                    value = ((List)value).get(0);
                }
            }
        }
        if (value == null) {
            value = getNullSourceValue();
        } else {
            BindingConverter converter = getSourceToTargetConverter(
                    resolver.getTypeOfLastProperty(), value, targetType);
            if (converter != null) {
                value = converter.sourceToTarget(value);
            }
        }
        return value;
    }
    
    /**
     * Returns the value from the target that should be set on the source.
     *
     * @throws ClassCastException if converter throws
     * @throws IllegalArgumentException if converter throws
     */
    private Object getValueForSource() throws PropertyResolverException {
        assert (sourceResolver.getEvaluationResultType() != INCOMPLETE_PATH && 
                targetResolver.hasAllPathValues());
        Object value;
        value = targetResolver.getValueOfLastProperty();
        if (value == null) {
            value = getNullTargetValue();
        } else {
            BindingConverter converter = getTargetToSourceConverter(
                    targetResolver.getTypeOfLastProperty(), value, sourceResolver.getTypeOfLastProperty());
            if (converter != null) {
                value = converter.targetToSource(value);
            }
        }
        return value;
    }
    
    private void unbindIfNecessary() {
        if (unbindOnCommit && targetValueState == ValueState.VALID) {
            unbindOnCommit = false;
            parentBinding.removeListBinding(this);
        }
    }
    
    private void setTargetValueState(ValueState state) {
        this.targetValueState = adjustTargetValueState(state);
    }
    
    /**
     * Returns the value state of the target.
     *
     * @return the value state of the target
     */
    public final ValueState getTargetValueState() {
        return targetValueState;
    }
    
    private void setSourceValueState(ValueState state) {
        this.sourceValueState = state;
    }
    
    /**
     * Returns the value state of the source.
     *
     * @return the value state of the source
     */
    public final ValueState getSourceValueState() {
        return sourceValueState;
    }
    
    private ValueState[] getValueState() {
        return new ValueState[] { getSourceValueState(),
                                  getTargetValueState() };
    }
    
    private ValueState adjustTargetValueState(ValueState state) {
        if (keepUncommitted && !unbindOnCommit && state == ValueState.VALID) {
            return ValueState.UNCOMMITTED;
        }
        return state;
    }
    
    private void notifyBindingContextIfNecessary(ValueState[] state, 
            Exception conversionException) {
        BindingContext context = getContext();
        if (isBound() && (state[0] != getSourceValueState() ||
                state[1] != getTargetValueState())) {
            if (bindingTarget != null && state[0] != getSourceValueState()) {
                bindingTarget.sourceValueStateChanged(getBindingController(), 
                        getTargetProperty());
            }
            if (context != null) {
                context.bindingValueStateChanged(this);
            }
        }
        if (conversionException != null) {
            if (bindingListeners != null) {
                for (BindingListener listener : bindingListeners) {
                    listener.converterFailed(this, conversionException);
                }
            }
            if (context != null) {
                context.converterFailed(this, conversionException);
            }
        }
    }
    
    private String getTargetProperty() {
        PropertyPath path = targetResolver.getPath();
        return path.get(path.length() - 1);
    }
    
    /**
     * Creates and adds a {@code Binding} as a child of this {@code Binding}.
     * Note: Child bindings are not to be added to a {@code BindingContext}.
     *
     * @param sourceExpression El expression specifying the "property" of the source
     * @param targetPath path to the property of the target
     * @return the {@code Binding}
     */
    public final Binding addChildBinding(String sourceExpression, String targetPath) {
        return addChildBinding(null, sourceExpression, targetPath);
    }

    /**
     * Creates and adds a {@code Binding} as a child of this {@code Binding}.
     * Note: Child bindings are not to be added to a {@code BindingContext}.
     *
     * @param name a name for the binding
     * @param sourceExpression El expression specifying the "property" of the source
     * @param targetPath path to the property of the target
     * @return the {@code Binding}
     * @throws IllegalArgumentException if this binding already has a child with
     *         the given name
     */
    public final Binding addChildBinding(String name, String sourceExpression, String targetPath) {
        Binding binding = new Binding(name, sourceExpression, targetPath);
        addChildBinding(binding);
        return binding;
    }

    /**
     * Adds a {@code Binding} as a child of this {@code Binding}.
     * Note: Child bindings are not to be added to a {@code BindingContext}.
     *
     * @param binding the {@code Binding} to add as a child
     * @throws IllegalStateException if this binding is bound or the given child
     *         binding is bound
     * @throws IllegalArgumentException if {@code binding} has already been
     *         added to a {@code Binding} or {@code BindingContext},
     *         or if this binding already has a child with the same name as
     *         the given binding
     * @throws NullPointerException if {@code binding} is {@code null}
     */
    public final void addChildBinding(Binding binding) {
        throwIfBound();
        binding.throwIfBound();

        if (binding.getParentBinding() != null) {
            throw new IllegalArgumentException("Can not add a Binding to two separate parents");
        }

        if (binding.getContext() != null) {
            throw new IllegalArgumentException("Binding in a context cannot be parented");
        }

        String name = binding.getName();
        if (name != null) {
            if (getChildBinding(name) != null) {
                throw new IllegalArgumentException("Binding already contains a child with name \"" + name + "\"");
            } else {
                putNamedChild(name, binding);
            }
        }

        binding.setParentBinding(this);

        List<Binding> childBindings;
        if (this.childBindings == null) {
            childBindings = new ArrayList<Binding>(1);
        } else {
            childBindings = new ArrayList<Binding>(this.childBindings);
        }
        childBindings.add(binding);
        this.childBindings = Collections.unmodifiableList(childBindings);
    }
    
    /**
     * Removes a previously added {@code Binding}.
     *
     * @param binding the {@code Binding} to remove
     * @throws NullPointerException if {@code binding} is {@code null}
     * @throws IllegalArgumentException if {@code binding} has not been added
     *         to this {@code Binding}
     * @throws IllegalStateException if bound, or if the child is bound
     */
    public final void removeChildBinding(Binding binding) {
        throwIfBound();
        binding.throwIfBound();

        if (binding.getParentBinding() != this) {
            throw new IllegalArgumentException("Binding is not a child of this Binding");
        }

        binding.setParentBinding(null);

        String name = binding.getName();
        if (name != null) {
            assert namedChildren != null;
            namedChildren.remove(name);
        }

        List<Binding> bindings = new ArrayList<Binding>(this.childBindings);
        bindings.remove(binding);
        childBindings = Collections.unmodifiableList(bindings);
    }

    /**
     * Returns the child binding with the given name, or {@code null}
     * if there is no such binding. Bindings without a name can not
     * be accessed by this method; it throws
     * {@code IllegalArgumentException} for a {@code null} name.
     *
     * @param name name of the binding to fetch
     * @return the child binding with the given name, or {@code null}
     * @throws IllegalArgumentException for a {@code null} name
     */
    public final Binding getChildBinding(String name) {
        if (name == null) {
            throw new IllegalArgumentException("cannot fetch unnamed bindings");
        }

        return namedChildren == null ? null : namedChildren.get(name);
    }

    /**
     * Returns a list of the child {@code Binding}s of this {@code Binding}.
     * The returned list is unmodifiable.
     *
     * @return a list of the child {@code Binding}s
     */
    public final List<Binding> getChildBindings() {
        if (this.childBindings == null) {
            return Collections.emptyList();
        }
        return this.childBindings;
    }
    
    private void removeListBinding(Binding binding) throws PropertyResolverException {
        throwIfNotBound();
        if (binding.isBound()) {
            binding.unbind();
        }
    }
    
    private void unbindChildOnCommit(Binding binding) {
        if (binding.getParentBinding() != this) {
            throw new IllegalArgumentException();
        }
        binding.unbindOnCommit();
    }
    
    private void unbindOnCommit() {
        unbindOnCommit = true;
    }
    
    private BindingController getBindingController() {
        if (bindingController == null) {
            bindingController = new BindingController();
        }
        return bindingController;
    }
    
    private void throwIfNotBound() {
        if (!bound) {
            throw new IllegalStateException("Must be bound to use this method");
        }
    }
    
    private void targetEdited() {
        BindingContext context = getContext();
        if (context != null) {
            context.targetEdited(this);
        }
    }

    private Object coerceValue(Class<?> type, Object value) {
        if (value == null && type.isPrimitive()) {
            if (type == boolean.class) {
                return Boolean.FALSE;
            } else if (type == byte.class) {
                return (byte)0;
            } else if (type == char.class) {
                return (char)0;
            } else if (type == short.class) {
                return (short)0;
            } else if (type == int.class) {
                return 0;
            } else if (type == long.class) {
                return 0l;
            } else if (type == float.class) {
                return 0f;
            } else if (type == double.class) {
                return (double)0;
            }
        }
        return value;
    }

    private void bindTo(Object source, Object target, String targetPath,
            boolean keepUncommitted) {
        throwIfBound();
        tmpSource = source;
        tmpTarget = target;
        tmpTargetPath = targetPath;
        this.keepUncommitted = keepUncommitted;
        bind();
    }

    private ELContext getELContext() {
        BindingContext context = getContext();
        if (context != null) {
            return context.getContext();
        }
        return new BindingContext().getContext();
    }

    /**
     * Returns a string representing the state of this binding.  This
     * method is intended to be used only for debugging purposes, and
     * the content and format of the returned string may vary between
     * implementations. The returned string may be empty but may not
     * be <code>null</code>.
     *
     * @return a string representation of this binding
     */
    public String toString() {
        return getClass().getName() + " [" + paramString() + "]";
    }
    
    /**
     * Returns a string representing the state of this binding.
     * This method is intended to be used only for
     * debugging purposes, and the content and format of the returned
     * string may vary between implementations. The returned string
     * may be empty but may not be {@code null}. Subclasses that override
     * this method should prepend the value from invoking
     * {@code super.paramString()} to the returned value, eg:
     * <pre>
     *   protected String paramString() {
     *       return super.paramString() + ", extraState=" + extraState;
     *   }
     * </pre>
     *
     * @return a string representing the state of this binding
     */
    private String paramString() {
        return "name=" + getName() +
               ", source=" + getSource() +
               ", sourceExpression=" + getSourceExpression() +
               ", target=" + getTarget() +
               ", targetPath=" + getTargetPath0() +
               ", bound=" + isBound() +
               ", validator=" + validator +
               ", converter=" + converter +
               ", valueForIncompleteTargetPath=" + incompleteTargetPathValue +
               ", valueForIncompleteSourcePath=" + incompleteSourcePathValue +
               ", updateStrategy=" + updateStrategy +
               ", sourceValueState=" + sourceValueState +
               ", targetValueState=" + targetValueState +
               ", childBindings=" + childBindings +
               ", keepUncommited=" + keepUncommitted +
               ", parameters=" + parameters;
    }


    
    /**
     * {@code BindingController} is used by {@code BindingTarget}s to control
     * the binding.
     *
     * @see javax.beans.binding.ext.BindingTarget
     */
    public final class BindingController {
        private BindingController() {
        }
        
        /**
         * Creates and returns an {@code ELPropertyResolver}.
         *
         * @return a new {@code ELPropertyResolver}
         */
        public ELPropertyResolver createResolver() {
            return new ELPropertyResolver(getELContext());
        }
        
        /**
         * Returns the value from the specified resolver.
         *
         * @param binding the {@code Binding} the resolver was created for
         * @param resolver the resolver to use for getting the value
         * @param targetType the expected type of the value
         *
         * @throws IllegalArgumentException if any of the arguments are 
         *         {@code null}
         */
        public Object getValueForTarget(Binding binding, 
                ELPropertyResolver resolver, Class<?> targetType) {
            BindingContext.throwIfNull(binding, resolver, targetType);
            return binding.getValueForTarget(resolver, targetType);
        }
        
        /**
         * Invoke to signal the target value has been edited. This changes the
         * target value state to {@code UNCOMMITTED} and notifies the
         * {@code BindingContext}.
         */
        public void valueEdited() {
            throwIfNotBound();
            ValueState[] oldState = getValueState();
            setTargetValueState(ValueState.UNCOMMITTED);
            notifyBindingContextIfNecessary(oldState, null);
            targetEdited();
        }
        
        /**
         * Returns the {@code Binding}.
         *
         * @return the {@code Binding}
         */
        public Binding getBinding() {
            return Binding.this;
        }
        
        /**
         * Schedules a {@code Binding} to unbind when the
         * {@code Binding} is committed.
         *
         * @param binding the {@code Binding} to remove
         * @throws NullPointerException if {@code binding} is {@code null}
         * @throws IllegalArgumentException if {@code binding} is not a
         *         child of the {@code Binding} returned by 
         *         {@code getBinding()}
         */
        public void unbindOnCommit(Binding binding) {
            Binding.this.unbindChildOnCommit(binding);
        }

        /**
         * Binds the specified child binding. The specified binding is bound
         * to the parameters, not those specified by the binding. In addition
         * this does not change the source, target or target path of the
         * binding in anyway, only what the binding is bound to.
         *
         * @param binding the {@code Binding} to bind
         * @param source the source for the binding
         * @param target the target for the binding
         * @param targetPath path for the target
         * @param keepUncommited if {@code true} the binding remains uncommited
         *        even after the value is set on the source from the target
         *
         * @throws IllegalArgumentException if the specified
         *         {@code Binding} is not a child of the Binding returned by
         *         {@code getBinding()}
         * @throws IllegalStateException if the given binding is already bound,
         *         or the source or target is {@code null}
         * @throws PropertyResolverException if {@code PropertyResolver} throws an
         *         exception; refer to {@code PropertyResolver} for the conditions
         *         under which an exception is thrown
         */
        public void bind(Binding binding, Object source, Object target,
                String targetPath, boolean keepUncommited) {
            if (binding.getParentBinding() != Binding.this) {
                throw new IllegalArgumentException();
            }
            binding.bindTo(source, target, targetPath, keepUncommited);
        }
    }
    
    
    private final class ELPropertyResolverDelegate extends 
            ELPropertyResolver.Delegate {
        public void valueChanged(ELPropertyResolver resolver) {
            sourceChanged();
        }
    }
    
    
    private final class PropertyResolverDelegate extends
            PropertyResolver.Delegate {
        public void valueChanged(PropertyResolver resolver) {
            targetChanged();
        }
    }


    /**
     * {@code ParameterKey} is used to provide additional piece(s) of
     * information (parameter(s)) to configure a specific binding.
     * Parameters are added to a {@code Binding} with the {@code putParameter}
     * method. {@code ParameterKey} identifies the parameter that is being put.
     *
     * @see #putParameter
     * @see #getParameter
     */
    public static class ParameterKey<T> {
        private final String description;

        /**
         * Creates a {@code ParameterKey} with the specified description.
         *
         * @param description a description of the {@code ParameterKey}
         * @throws IllegalArgumentException if {@code description} is {@code null}
         */
        public ParameterKey(String description) {
            if (description == null) {
                throw new IllegalArgumentException("Description must be non-null");
            }
            this.description = description;
        }

        /**
         * Returns a description of the {@code ParameterKey}.
         *
         * @return a description of the {@code ParameterKey}
         */
        public final String getDescription() {
            return description;
        }

        /**
         * Returns a string representing this {@code ParameterKey}.
         * This method is intended to be used only for debugging purposes, and
         * the content and format of the returned string may vary between
         * implementations. The returned string may be empty but may not
         * be {@code null}.
         *
         * @return a string representation of this {@code ParameterKey}
         */
        public String toString() {
            return getClass() + " [" + " description=" + description + "]";
        }
    }
}
