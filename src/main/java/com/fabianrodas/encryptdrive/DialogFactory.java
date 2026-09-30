package com.fabianrodas.encryptdrive;

import java.io.IOException;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Modal popups in the application's visual style. Dialogs only report the
 * user's intent; callers decide what to persist.
 */
final class DialogFactory {

    private DialogFactory() {
    }

    /** Returns true only when the user explicitly confirms. */
    static boolean confirm(Window owner, String title, String message, String confirmText) {
        FXMLLoader loader = new FXMLLoader(App.class.getResource("confirmation-popup.fxml"));
        Parent popupRoot;

        try {
            popupRoot = loader.load();
        } catch (IOException e) {
            return false;
        }

        ConfirmationPopupController controller = loader.getController();
        controller.setContent(title, message, confirmText);
        showModal(owner, popupRoot);
        return controller.isConfirmed();
    }

    private static void showModal(Window owner, Parent popupRoot) {
        Stage popupStage = new Stage();

        if (owner != null) {
            popupStage.initOwner(owner);
        }

        popupStage.initModality(Modality.APPLICATION_MODAL);
        popupStage.initStyle(StageStyle.UNDECORATED);
        popupStage.setResizable(false);
        popupStage.setScene(new Scene(popupRoot));
        popupStage.showAndWait();
    }
}
