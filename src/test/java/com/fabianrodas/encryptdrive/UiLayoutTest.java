package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.services.VaultSessionService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * Loads each screen with its real controller at the minimum window size and
 * at a maximized desktop size, and fails if any button or input field falls
 * outside the window (content inside a ScrollPane may scroll instead).
 */
class UiLayoutTest {

    private static final double[][] SIZES = {{1000, 600}, {1920, 1040}};

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startJavaFxWithASignedInUser() throws Exception {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");

        VaultContext vault = new VaultService().createVault(
                tempDir.resolve("vault"), "correct vault password".toCharArray()
        );
        AuthService auth = new AuthService(vault);
        auth.register("Example User", "ExampleUser", "example password".toCharArray());
        UserLoginResult login = auth.login("ExampleUser", "example password".toCharArray());
        SessionService.start(login.identity(), login.userMasterKey());

        FileService files = FileService.forCurrentSession();
        ManifestEntry documents = files.createFolder("Documents", files.rootFolderId());
        ManifestEntry projects = files.createFolder("Projects 2026", documents.getEntryId());
        files.createFolder("Quarterly planning", projects.getEntryId());
    }

    @AfterAll
    static void closeVault() {
        VaultSessionService.closeVault();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "#overviewNavButton", "#filesNavButton", "#trashNavButton",
        "#profileNavButton", "#settingsNavButton"
    })
    void workspaceViewsFit(String navigationButton) throws Exception {
        assertFits("dashboard", root -> fire(root, navigationButton));
    }

    /** Search results add a LOCATION column and a Clear button: they must still fit the window. */
    @ParameterizedTest
    @CsvSource({"1000,600", "1920,1040"})
    void filesWithSearchResultsFit(double width, double height) throws Exception {
        Parent root = FxTestSupport.onFxThread(() -> {
            Parent dashboard = FXMLLoader.load(App.class.getResource("dashboard.fxml"));
            new Scene(dashboard, width, height);
            fire(dashboard, "#filesNavButton");
            return dashboard;
        });
        FxTestSupport.waitUntil(() -> !table(root).getItems().isEmpty());
        FxTestSupport.onFxThread(() -> {
            TextField field = (TextField) root.lookup("#searchField");
            field.setText("planning");
            field.fireEvent(new ActionEvent());
            return null;
        });
        FxTestSupport.waitUntil(() -> root.lookup("#clearSearchButton").isVisible());
        // A table outside a showing window only brings up its rows when laid out.
        FxTestSupport.waitUntil(() -> {
            root.resize(width, height);
            root.applyCss();
            root.layout();
            return !locationCells(root).isEmpty();
        });

        List<String> problems = FxTestSupport.onFxThread(() -> {
            List<String> found = new ArrayList<>();
            collectOutside(root, width, height, found);
            TableView<?> table = table(root);
            double columns = table.getVisibleLeafColumns().stream().mapToDouble(TableColumn::getWidth).sum();
            double location = table.getColumns().get(table.getColumns().size() - 1).getWidth();

            if (columns > table.getWidth() + 0.5) {
                found.add("the columns are " + columns + " wide in a table " + table.getWidth() + " wide");
            }

            if (location < 120) {
                found.add("the LOCATION column is only " + location + " wide");
            }

            // A path that does not fit loses its start, so the folder the file is in stays visible.
            for (TableCell<?, ?> cell : locationCells(root)) {
                if (cell.getTextOverrun() != OverrunStyle.LEADING_ELLIPSIS) {
                    found.add("LOCATION cell \"" + cell.getText() + "\" shortens with " + cell.getTextOverrun());
                }
            }

            return found;
        });

        assertEquals(List.of(), problems, "Files with search results at " + width + "x" + height);
    }

    /** The LOCATION cells that show a path; call on the JavaFX thread. */
    private static List<TableCell<?, ?>> locationCells(Parent root) {
        TableView<?> table = table(root);
        TableColumn<?, ?> location = table.getColumns().get(table.getColumns().size() - 1);
        List<TableCell<?, ?>> cells = new ArrayList<>();

        for (Node node : root.lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> cell
                    && cell.getTableColumn() == location
                    && !cell.isEmpty()
                    && cell.getText() != null
                    && !cell.getText().isEmpty()) {
                cells.add(cell);
            }
        }

        return cells;
    }

    @SuppressWarnings("unchecked")
    private static TableView<ManifestEntry> table(Parent root) {
        return (TableView<ManifestEntry>) root.lookup("#table");
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
