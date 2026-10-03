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
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/*
 * The breadcrumb is the only way to navigate up, so inside the dashboard it
 * must show a path three folders deep in full, not ellipsized, at the minimum
 * window size and at a maximized desktop size.
 */
class FilesBreadcrumbLayoutTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startJavaFxWithANestedFolder() throws Exception {
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
        files.createFolder("Reports", projects.getEntryId());
    }

    @AfterAll
    static void closeVault() {
        VaultSessionService.closeVault();
    }

    @ParameterizedTest
    @CsvSource({"1000,600", "1920,1040"})
    void aPathThreeFoldersDeepIsShownInFull(double width, double height) throws Exception {
        Parent root = FxTestSupport.onFxThread(() -> {
            Parent dashboard = FXMLLoader.load(App.class.getResource("dashboard.fxml"));
            new Scene(dashboard, width, height);
            ((ButtonBase) dashboard.lookup("#filesNavButton")).fire();
            return dashboard;
        });

        // Opens Documents, then Projects 2026, then Reports, the way a double-click does.
        for (int depth = 0; depth < 3; depth++) {
            int shown = 2 * depth + 1;
            FxTestSupport.waitUntil(() -> {
                layout(root, width, height);
                return breadcrumbBar(root).getChildren().size() == shown && !table(root).getItems().isEmpty();
            });
            FxTestSupport.onFxThread(() -> {
                layout(root, width, height);
                doubleClick(firstRow(root));
                return null;
            });
        }

        FxTestSupport.waitUntil(() -> breadcrumbBar(root).getChildren().size() == 7);

        List<String> problems = FxTestSupport.onFxThread(() -> {
            layout(root, width, height);
            HBox bar = breadcrumbBar(root);
            List<String> found = new ArrayList<>();
            List<String> texts = new ArrayList<>();

            if (bar.getWidth() + 0.5 < bar.prefWidth(-1)) {
                found.add("#breadcrumbBar is " + bar.getWidth() + " wide but needs " + bar.prefWidth(-1));
            }

            for (Node node : bar.getChildren()) {
                Region part = (Region) node;
                Bounds bounds = part.localToScene(part.getLayoutBounds());
                texts.add(((Labeled) part).getText());

                if (part.getWidth() + 0.5 < part.prefWidth(-1)) {
                    found.add(texts.get(texts.size() - 1) + " is " + part.getWidth() + " wide but needs " + part.prefWidth(-1));
                }

                if (bounds.getMinX() < -0.5 || bounds.getMinY() < -0.5
                        || bounds.getMaxX() > width + 0.5 || bounds.getMaxY() > height + 0.5) {
                    found.add(texts.get(texts.size() - 1) + " is outside the window " + bounds);
                }
            }

            assertEquals(List.of("My files", "›", "Documents", "›", "Projects 2026", "›", "Reports"), texts);
            return found;
        });

        assertEquals(List.of(), problems, "breadcrumb at " + width + "x" + height);
    }

    private static void layout(Parent root, double width, double height) {
        root.resize(width, height);
        root.applyCss();
        root.layout();
    }

    private static HBox breadcrumbBar(Parent root) {
        return (HBox) root.lookup("#breadcrumbBar");
    }

    @SuppressWarnings("unchecked")
    private static TableView<ManifestEntry> table(Parent root) {
        return (TableView<ManifestEntry>) root.lookup("#table");
    }

    private static TableRow<?> firstRow(Parent root) {
        for (Node node : root.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row && !row.isEmpty()) {
                return row;
            }
        }

        throw new AssertionError("The folder shows no row to open");
    }

    private static void doubleClick(TableRow<?> row) {
        Event.fireEvent(row, new MouseEvent(
                MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, true, false, true, null
        ));
    }
}
