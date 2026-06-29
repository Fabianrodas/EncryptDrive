package com.fabianrodas.utils;

import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Utils class
 * 
 * @author Fabian Rodas
 */

public final class WindowDragHandler {

    private static final double DEFAULT_WIDTH = 1000;
    private static final double DEFAULT_HEIGHT = 600;
    private static final double TITLE_BAR_HEIGHT = 38;

    private double xOffset;
    private double yOffset;
    private double horizontalRatio;

    private double lastNormalWidth = DEFAULT_WIDTH;
    private double lastNormalHeight = DEFAULT_HEIGHT;

    public void beginDrag(MouseEvent event, Stage stage) {
        if (stage == null) {
            return;
        }

        /*
         * Save the normal window dimensions before maximizing.
         * They will be used when the window is restored by dragging.
         */
        if (!stage.isMaximized()) {
            lastNormalWidth = stage.getWidth();
            lastNormalHeight = stage.getHeight();
        }

        xOffset = event.getSceneX();
        yOffset = event.getSceneY();

        horizontalRatio = event.getSceneX()
                / Math.max(stage.getWidth(), 1);
    }

    public void dragWindow(MouseEvent event, Stage stage) {
        if (stage == null) {
            return;
        }

        /*
         * When the user starts dragging while maximized:
         * 1. Restore the previous normal size.
         * 2. Keep the cursor in a proportional horizontal position.
         * 3. Continue moving the window normally.
         */
        if (stage.isMaximized()) {
            stage.setMaximized(false);

            stage.setWidth(lastNormalWidth);
            stage.setHeight(lastNormalHeight);

            double safeRatio = Math.max(
                    0,
                    Math.min(1, horizontalRatio)
            );

            xOffset = lastNormalWidth * safeRatio;
            yOffset = Math.min(yOffset, TITLE_BAR_HEIGHT);
        }

        stage.setX(event.getScreenX() - xOffset);
        stage.setY(event.getScreenY() - yOffset);
    }
}