/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Window;
import java.util.*;

/** App-scoped UI observation; no input injection or desktop capture. */
final class UiRecording implements AutoCloseable {
    private final RecordWriter writer;
    private final Runnable activity;
    private final Map<Scene, List<Runnable>> scenes = new IdentityHashMap<>();
    private final Map<MenuItem, List<Runnable>> menus = new IdentityHashMap<>();
    private final ListChangeListener<Window> windows;
    private boolean closed;

    UiRecording(RecordWriter writer, Runnable activity) {
        this.writer = writer;
        this.activity = activity;
        windows = change -> writer.observe("windows", () -> {
            while (change.next()) {
                for (var window : change.getAddedSubList()) observeWindow(window);
                for (var window : change.getRemoved()) {
                    emit("window.hidden", Map.of("window", windowName(window)));
                    releaseScene(window.getScene());
                    if (window instanceof ContextMenu context) releaseMenus(context.getItems());
                }
            }
        });
        Window.getWindows().addListener(windows);
        for (var window : Window.getWindows()) writer.observe("window.baseline", () -> observeWindow(window));
    }

    void observeWindow(Window window) {
        emit("window.shown", Map.of("window", windowName(window)));
        observeScene(window.getScene());
        if (window instanceof ContextMenu context) observeMenus(context.getItems());
    }

    void observeScene(Scene scene) {
        if (scene == null || scenes.containsKey(scene)) return;
        var removals = new ArrayList<Runnable>();
        scenes.put(scene, removals);
        EventHandler<ActionEvent> action = protect("ui.action", event -> {
            if (event.getTarget() instanceof Node node) {
                emit("ui.action", describe(node));
                captureForm(node.getScene());
            }
        });
        EventHandler<MouseEvent> mouse = protect("ui.pointer", event -> {
            if (event.getTarget() instanceof Node node) {
                var fields = describe(node);
                fields.put("event", event.getEventType().getName());
                fields.put("sceneX", event.getSceneX()); fields.put("sceneY", event.getSceneY());
                fields.put("button", event.getButton().name()); fields.put("clickCount", event.getClickCount());
                emit("ui.pointer", fields);
                if (event.getEventType() == MouseEvent.MOUSE_RELEASED) captureForm(scene);
            }
        });
        EventHandler<KeyEvent> key = protect("ui.key", event -> {
            // Text is captured as committed field values, never a system-wide keystroke stream.
            if (event.getTarget() instanceof PasswordField) return;
            boolean textEntry = event.getTarget() instanceof TextInputControl;
            if (event.isShortcutDown() || !textEntry || event.getCode().isNavigationKey() || event.getCode().name().equals("ENTER"))
                emit("ui.key", Map.of("code", event.getCode().name(), "control", event.isControlDown(),
                        "alt", event.isAltDown(), "shift", event.isShiftDown(), "meta", event.isMetaDown(),
                        "target", event.getTarget() instanceof Node node ? describe(node) : Map.of()));
        });
        EventHandler<ScrollEvent> scroll = protect("ui.scroll", event -> emit("ui.scroll", Map.of("deltaX", event.getDeltaX(),
                "deltaY", event.getDeltaY(), "sceneX", event.getSceneX(), "sceneY", event.getSceneY())));
        ChangeListener<Node> focus = (observable, old, next) -> writer.observe("ui.field", () -> {
            if (old instanceof TextInputControl) emit("ui.field", describe(old));
        });
        scene.addEventFilter(ActionEvent.ACTION, action);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, mouse);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, mouse);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, key);
        scene.addEventFilter(ScrollEvent.SCROLL, scroll);
        scene.focusOwnerProperty().addListener(focus);
        removals.add(() -> {
            scene.removeEventFilter(ActionEvent.ACTION, action);
            scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, mouse);
            scene.removeEventFilter(MouseEvent.MOUSE_RELEASED, mouse);
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, key);
            scene.removeEventFilter(ScrollEvent.SCROLL, scroll);
            scene.focusOwnerProperty().removeListener(focus);
        });
        scanMenus(scene.getRoot());
        captureForm(scene);
    }

    void observeMenus(List<? extends MenuItem> items) {
        for (var item : items) {
            if (menus.containsKey(item)) continue;
            var removals = new ArrayList<Runnable>();
            menus.put(item, removals);
            EventHandler<ActionEvent> handler = protect("ui.menu", event -> {
                if (event.getTarget() == item) emit("ui.menu", Map.of("path", menuPath(item),
                        "selected", item instanceof CheckMenuItem check && check.isSelected()));
            });
            item.addEventHandler(ActionEvent.ACTION, handler);
            removals.add(() -> item.removeEventHandler(ActionEvent.ACTION, handler));
            if (item instanceof Menu menu) {
                observeMenus(menu.getItems());
                EventHandler<javafx.event.Event> showing = protect("ui.menu.show", event -> observeMenus(menu.getItems()));
                menu.addEventHandler(Menu.ON_SHOWING, showing);
                removals.add(() -> menu.removeEventHandler(Menu.ON_SHOWING, showing));
            }
        }
    }

    private void scanMenus(Node node) {
        if (node instanceof MenuBar bar) observeMenus(bar.getMenus());
        if (node instanceof MenuButton button) observeMenus(button.getItems());
        if (node instanceof Control control && control.getContextMenu() != null) observeMenus(control.getContextMenu().getItems());
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(this::scanMenus);
    }

    void releaseScene(Scene scene) {
        var removals = scenes.remove(scene);
        if (removals == null) return;
        removals.forEach(Runnable::run);
        releaseNodeMenus(scene.getRoot());
    }

    private void releaseNodeMenus(Node node) {
        if (node instanceof MenuBar bar) releaseMenus(bar.getMenus());
        if (node instanceof MenuButton button) releaseMenus(button.getItems());
        if (node instanceof Control control && control.getContextMenu() != null) releaseMenus(control.getContextMenu().getItems());
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(this::releaseNodeMenus);
    }

    private void releaseMenus(List<? extends MenuItem> items) {
        for (var item : items) {
            if (item instanceof Menu menu) releaseMenus(menu.getItems());
            var removals = menus.remove(item);
            if (removals != null) removals.forEach(Runnable::run);
        }
    }

    private void captureForm(Scene scene) {
        if (scene == null) return;
        var values = new ArrayList<Map<String, Object>>();
        collect(scene.getRoot(), values);
        if (!values.isEmpty()) emit("ui.form", Map.of("window", windowName(scene.getWindow()), "controls", values));
        scanMenus(scene.getRoot());
    }

    private void collect(Node node, List<Map<String, Object>> values) {
        if (node instanceof PasswordField) return;
        if (node instanceof TextField || node instanceof CheckBox || node instanceof ComboBox<?> ||
                node instanceof ChoiceBox<?> || node instanceof Spinner<?> || node instanceof Slider)
            values.add(describe(node));
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> collect(child, values));
    }

    static Map<String, Object> describe(Node node) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("type", node.getClass().getSimpleName()); fields.put("id", node.getId());
        var path = new ArrayList<String>();
        for (Node current = node; current != null && path.size() < 12; current = current.getParent())
            path.add(current.getClass().getSimpleName() + (current.getId() == null ? "" : "#" + current.getId()));
        Collections.reverse(path); fields.put("path", path);
        if (node instanceof Labeled label) fields.put("label", label.getText());
        if (node instanceof PasswordField) fields.put("value", "[password omitted]");
        else if (node instanceof TextField field) { fields.put("value", field.getText()); fields.put("prompt", field.getPromptText()); }
        else if (node instanceof TextArea area) fields.put("textLength", area.getLength());
        else if (node instanceof ComboBox<?> combo) fields.put("value", Objects.toString(combo.getValue(), ""));
        else if (node instanceof ChoiceBox<?> choice) fields.put("value", Objects.toString(choice.getValue(), ""));
        else if (node instanceof Spinner<?> spinner) fields.put("value", Objects.toString(spinner.getValue(), ""));
        else if (node instanceof Slider slider) fields.put("value", slider.getValue());
        if (node instanceof CheckBox check) fields.put("selected", check.isSelected());
        return fields;
    }

    private static String menuPath(MenuItem item) {
        var path = new ArrayList<String>();
        for (var current = item; current != null; current = current.getParentMenu()) path.add(Objects.toString(current.getText(), ""));
        Collections.reverse(path);
        return String.join(" > ", path);
    }

    private static String windowName(Window window) {
        return window instanceof javafx.stage.Stage stage ? Objects.toString(stage.getTitle(), "") :
                window == null ? "unattached" : window.getClass().getSimpleName();
    }

    private void emit(String type, Map<String, ?> fields) {
        if (closed) return;
        writer.event(type, fields);
        activity.run();
    }

    private <T extends javafx.event.Event> EventHandler<T> protect(String source, EventHandler<T> handler) {
        return event -> writer.observe(source, () -> handler.handle(event));
    }

    @Override public void close() {
        closed = true;
        Window.getWindows().removeListener(windows);
        new ArrayList<>(scenes.keySet()).forEach(this::releaseScene);
        menus.values().forEach(removals -> removals.forEach(Runnable::run));
        menus.clear();
    }
}
