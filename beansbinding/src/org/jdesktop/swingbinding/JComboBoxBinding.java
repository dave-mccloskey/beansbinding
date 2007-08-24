/*
 * Copyright (C) 2007 Sun Microsystems, Inc. All rights reserved. Use is
 * subject to license terms.
 */

package org.jdesktop.swingbinding;

import javax.swing.*;
import javax.swing.event.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jdesktop.beansbinding.AutoBinding;
import org.jdesktop.beansbinding.ObjectProperty;
import org.jdesktop.beansbinding.Property;
import org.jdesktop.beansbinding.PropertyStateEvent;
import org.jdesktop.beansbinding.PropertyStateListener;
import org.jdesktop.swingbinding.impl.ColumnBinding;
import org.jdesktop.swingbinding.impl.ListBindingManager;

/**
 * @author Shannon Hickey
 */
public final class JComboBoxBinding<E, SS, TS> extends AutoBinding<SS, List<E>, TS, List> {

    private ElementsProperty<TS, JComboBox> ep;
    private Handler handler = new Handler();
    private BindingComboBoxModel model;
    private JComboBox combo;
    private ListDetailBinding detailBinding;

    protected JComboBoxBinding(UpdateStrategy strategy, SS sourceObject, Property<SS, List<E>> sourceListProperty, TS targetObject, Property<TS, ? extends JComboBox> targetJComboBoxProperty, String name) {
        super(strategy, sourceObject, sourceListProperty, targetObject, new ElementsProperty<TS, JComboBox>(targetJComboBoxProperty), name);
        ep = (ElementsProperty<TS, JComboBox>)getTargetProperty();
        setDetailBinding(null);
    }

    protected void bindImpl() {
        model = new BindingComboBoxModel();
        // order is important for the next two lines
        ep.addPropertyStateListener(null, handler);
        ep.installBinding(this);
        super.bindImpl();
    }

    protected void unbindImpl() {
        // order is important for the next two lines
        ep.uninstallBinding();
        ep.removePropertyStateListener(null, handler);
        model = null;
        super.unbindImpl();
    }

    public ListDetailBinding setDetailBinding(Property<E, ?> detailProperty) {
        return detailProperty == null ?
            setDetailBinding(ObjectProperty.<E>create(), "AUTO_DETAIL") :
            setDetailBinding(detailProperty, null);
    }

    public ListDetailBinding setDetailBinding(Property<E, ?> detailProperty, String name) {
        throwIfBound();

        if (detailProperty == null) {
            throw new IllegalArgumentException("can't have null detail property");
        }

        detailBinding = new ListDetailBinding(detailProperty, name);
        return detailBinding;
    }

    public ListDetailBinding getDetailBinding() {
        return detailBinding;
    }

    private final Property DETAIL_PROPERTY = new Property() {
        public Class<Object> getWriteType(Object source) {
            return Object.class;
        }

        public Object getValue(Object source) {
            throw new UnsupportedOperationException();
        }

        public void setValue(Object source, Object value) {
            throw new UnsupportedOperationException();
        }

        public boolean isReadable(Object source) {
            throw new UnsupportedOperationException();
        }

        public boolean isWriteable(Object source) {
            return true;
        }

        public void addPropertyStateListener(Object source, PropertyStateListener listener) {
            throw new UnsupportedOperationException();
        }

        public void removePropertyStateListener(Object source, PropertyStateListener listener) {
            throw new UnsupportedOperationException();
        }

        public PropertyStateListener[] getPropertyStateListeners(Object source) {
            throw new UnsupportedOperationException();
        }
    };

    public final class ListDetailBinding extends ColumnBinding {

        public ListDetailBinding(Property<E, ?> detailProperty, String name) {
            super(0, detailProperty, DETAIL_PROPERTY, name);
        }

        private void setSourceObjectInternal(Object object) {
            setManaged(false);
            try {
                setSourceObject(object);
            } finally {
                setManaged(true);
            }
        }
    }

    private class Handler implements PropertyStateListener {
        public void propertyStateChanged(PropertyStateEvent pse) {
            if (!pse.getValueChanged()) {
                return;
            }

            Object newValue = pse.getNewValue();

            if (newValue == PropertyStateEvent.UNREADABLE) {
                combo.setModel(new DefaultComboBoxModel());
                combo = null;
                model.setElements(null);
            } else {
                combo = ep.getComponent();
                model.setElements((List<E>)newValue);
                combo.setModel(model);
            }
        }
    }

    private static final boolean areObjectsEqual(Object o1, Object o2) {
        return ((o1 != null && o1.equals(o2)) ||
                (o1 == null && o2 == null));
    }
    
    private final class BindingComboBoxModel extends ListBindingManager implements ComboBoxModel  {
        private final List<ListDataListener> listeners;
        private Object selectedObject;

        public BindingComboBoxModel() {
            listeners = new CopyOnWriteArrayList<ListDataListener>();
        }

        public void setElements(List<?> elements) {
            super.setElements(elements);
            if (size() > 0) {
                selectedObject = getElementAt(0);
            }
        }
        
        protected ColumnBinding[] getColBindings() {
            return new ColumnBinding[] {getDetailBinding()};
        }

        public Object getSelectedItem() {
            return selectedObject;
        }

        public void setSelectedItem(Object anObject) {
            // This is what DefaultComboBoxModel does (yes, yuck!)
            if ((selectedObject != null && !selectedObject.equals(anObject)) ||
                    selectedObject == null && anObject != null) {
                selectedObject = anObject;
                contentsChanged(-1, -1);
            }
        }

        protected void allChanged() {
            contentsChanged(0, size());
        }

        protected void valueChanged(int row, int column) {
            contentsChanged(row, row);
        }

        protected void added(int index, int length) {
            ListDataEvent e = new ListDataEvent(this, ListDataEvent.INTERVAL_ADDED, index, index + length - 1);
            for (ListDataListener listener : listeners) {
                listener.intervalAdded(e);
            }

            if (size() == length && selectedObject == null && getElementAt(0) != null) {
                setSelectedItem(getElementAt(0));
            }
        }

        protected void removed(int index, List<Object> elements) {
            boolean removedSelected = false;
            int length = elements.size();

            try {
                for (Object element : elements) {
                    detailBinding.setSourceObjectInternal(element);
                    Object detail = detailBinding.getSourceValueForTarget().getValue();
                    if (areObjectsEqual(detail, selectedObject)) {
                        removedSelected = true;
                        break;
                    }
                }
            } finally {
                detailBinding.setSourceObjectInternal(null);
            }

            ListDataEvent e = new ListDataEvent(this, ListDataEvent.INTERVAL_REMOVED, index, index + length - 1);
            for (ListDataListener listener : listeners) {
                listener.intervalRemoved(e);
            }

            if (removedSelected) {
                if (size() == 0) {
                    setSelectedItem(null);
                } else {
                    setSelectedItem(getElementAt(Math.max(index - 1, 0)));
                }
            }
        }

        protected void changed(int row) {
            contentsChanged(row, row);
        }

        private void contentsChanged(int row0, int row1) {
            ListDataEvent e = new ListDataEvent(this, ListDataEvent.CONTENTS_CHANGED, row0, row1);
            for (ListDataListener listener : listeners) {
                listener.contentsChanged(e);
            }
        }

        public Object getElementAt(int index) {
            return valueAt(index, 0);
        }

        public void addListDataListener(ListDataListener l) {
            listeners.add(l);
        }

        public void removeListDataListener(ListDataListener l) {
            listeners.remove(l);
        }

        public int getSize() {
            return size();
        }
    }
}
