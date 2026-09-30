package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/*
 * Loads each screen with its real controller at the minimum window size and
 * at a maximized desktop size, and fails if any button or input field falls
 * outside the window (content inside a ScrollPane may scroll instead).
 */
class UiLayoutTest {

    private static final double[][] SIZES = {{1000, 600}, {1920, 1040}};

    @BeforeAll
    static void startJavaFx() {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");
    }

    @Test
    void vaultSelectionOpenModeFits() throws Exception {
        assertFits("vault-selection", root -> { });
    }

    @Test
    void vaultSelectionCreateModeFits() throws Exception {
        assertFits("vault-selection", root -> fire(root, "#createModeButton"));
    }

    @Test
    void loginFits() throws Exception {
        assertFits("login", root -> { });
    }

    @Test
    void registerFits() throws Exception {
        assertFits("register", root -> { });
    }

    @Test
    void dashboardFits() throws Exception {
        assertFits("dashboard", root -> { });
    }

    private static void assertFits(String fxml, Consumer<Parent> prepare) throws Exception {
        for (double[] size : SIZES) {
            List<String> outside = FxTestSupport.onFxThread(() -> {
                Parent root = FXMLLoader.load(App.class.getResource(fxml + ".fxml"));
                new Scene(root, size[0], size[1]);
                prepare.accept(root);
                root.resize(size[0], size[1]);
                root.applyCss();
                root.layout();

                List<String> found = new ArrayList<>();
                collectOutside(root, size[0], size[1], found);
                return found;
            });

            assertEquals(List.of(), outside, fxml + " at " + size[0] + "x" + size[1]);
        }
    }

    static void fire(Parent root, String selector) {
        ((ButtonBase) root.lookup(selector)).fire();
    }

    private static void collectOutside(Node node, double width, double height, List<String> found) {
        if (!node.isVisible() || node instanceof ScrollPane) {
            return;
        }

        if (node instanceof ButtonBase || node instanceof TextInputControl) {
            Bounds bounds = node.localToScene(node.getLayoutBounds());

            if (bounds.getMinX() < -0.5
                    || bounds.getMinY() < -0.5
                    || bounds.getMaxX() > width + 0.5
                    || bounds.getMaxY() > height + 0.5) {
                found.add(describe(node) + " " + bounds);
            }
        }

        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectOutside(child, width, height, found);
            }
        }
    }

    private static String describe(Node node) {
        if (node.getId() != null) {
            return "#" + node.getId();
        }

        return node instanceof Labeled labeled
                ? node.getClass().getSimpleName() + "[" + labeled.getText() + "]"
                : node.getClass().getSimpleName();
    }
}
