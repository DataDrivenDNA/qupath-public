/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */

package qupath.lib.gui.measure;

import javafx.beans.value.ObservableValue;
import qupath.lib.lazy.interfaces.LazyValue;

/** A display value that notifies consumers when an asynchronous result becomes available. */
interface ObservableLazyValue<S, T> extends LazyValue<S, T> {
    ObservableValue<?> getObservable();

    /** Request a display value without waiting for its calculation. */
    T getValueForDisplay(S input);

    String getStringValueForDisplay(S input, int decimalPlaces);

    /** Cancel outstanding display work when the containing table model is replaced. */
    void cancelPending();
}
