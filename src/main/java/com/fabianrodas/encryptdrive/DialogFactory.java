package com.fabianrodas.encryptdrive;

import java.io.IOException;
import java.util.Optional;
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
        ConfirmationPopupController popup = open(title, message, confirmText);

        if (popup == null) {
            return false;
        }

        stage(owner, popup).showAndWait();
        return popup.isConfirmed();
    }

    /** Like {@link #confirm}, styled for actions that cannot be undone. */
    static boolean confirmDestructive(
            Window owner,
            String title,
            String message,
            String confirmText
    ) {
        ConfirmationPopupController popup = open(title, message, confirmText);

        if (popup == null) {
            return false;
        }

        popup.setDestructive();
        stage(owner, popup).showAndWait();
        return popup.isConfirmed();
    }

    /** Shows a message with a single OK button and returns at once. */
    static void inform(Window owner, String title, String message) {
        ConfirmationPopupController popup = open(title, message, "OK");

        if (popup != null) {
            popup.setInformational();
            stage(owner, popup).show();
        }
    }

    /** Returns the entered text, or empty when the user cancels. */
    static Optional<String> prompt(
            Window owner,
            String title,
            String message,
            String initialValue,
            String confirmText
    ) {
        ConfirmationPopupController popup = open(title, message, confirmText);

        if (popup == null) {
            return Optional.empty();
        }

        popup.setPrompt(initialValue);
        stage(owner, popup).showAndWait();
        return popup.isConfirmed() ? Optional.of(popup.getInput()) : Optional.empty();
    }

    private static ConfirmationPopupController open(
            String title,
            String message,
            String confirmText
    ) {
        FXMLLoader loader = new FXMLLoader(App.class.getResource("confirmation-popup.fxml"));

        try {
            loader.load();
        } catch (IOException e) {
            return null;
        }

        ConfirmationPopupController popup = loader.getController();
        popup.setContent(title, message, confirmText);
        return popup;
    }

    private static Stage stage(Window owner, ConfirmationPopupController popup) {
        Stage popupStage = new Stage();

        if (owner != null) {
            popupStage.initOwner(owner);
        }

        popupStage.initModality(Modality.APPLICATION_MODAL);
        popupStage.initStyle(StageStyle.UNDECORATED);
        popupStage.setResizable(false);
        popupStage.setScene(new Scene((Parent) popup.root()));
        return popupStage;
    }
}
